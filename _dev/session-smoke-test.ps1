<#
    "Development session" smoke test - clinexa project (guide 5.7, SEC-05)

    A test session in under a minute. This is the explicit counterpart of SEC-05:
    there is NO profile without security, so the team has to be able to get an
    authenticated session with no friction - otherwise they'd work around security instead of using it.

    Validates, in order: identity-service reachable -> CSRF token obtained -> login
    -> /api/v1/me read with the cookie -> the same session accepted by care-service (shared
    Redis) -> logout -> the session is worth nothing anymore.

    Prerequisites: docker compose up -d, then config-server, discovery-server, identity-service
    (dev profile), care-service, api-gateway. The dev profile provisions the accounts.

    Usage: .\session-smoke-test.ps1
           .\session-smoke-test.ps1 -Utilisateur erin@clinexa.ma
           .\session-smoke-test.ps1 -BaseUrl http://localhost:9000   # through the gateway
#>

param(
    [string]$BaseUrl      = "http://localhost:8100",   # identity-service directly
    [string]$BaseUrlCare  = "http://localhost:8101",   # care-service directly
    [string]$Utilisateur  = "erin@clinexa.ma",         # the fixture that belongs to both clinics
    [string]$MotDePasse   = "Clinexa!2026"
)

$script:AllPassed = $true
$continue         = $true
$session          = $null
$moi              = $null

function Show-Header($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Show-Ok($m)     { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Show-Fail($m)   { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:AllPassed = $false }
function Show-Hint($m)   { Write-Host "         -> $m" -ForegroundColor DarkYellow }
function Show-Skip($m)   { Write-Host "  [SKIP] $m" -ForegroundColor DarkGray }
function Show-Info($m)   { Write-Host "         $m" -ForegroundColor Gray }

try {
    # --- 0. identity-service reachable ---
    Show-Header "0. identity-service"
    try {
        $sante = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
        if ($sante.status -eq "UP") {
            Show-Ok "identity-service responds UP on $BaseUrl."
        } else {
            Show-Fail "identity-service responds '$($sante.status)'."
            $continue = $false
        }
    } catch {
        Show-Fail "identity-service unreachable on $BaseUrl."
        Show-Hint "Startup order: docker compose -> config-server (8888) -> discovery-server (8761) -> identity-service (8100)."
        $continue = $false
    }

    # --- 1. CSRF token ---
    # CSRF is on, and it's not negotiable: session-cookie authentication is inherently
    # vulnerable to cross-site forged requests. The XSRF-TOKEN cookie is readable by
    # JavaScript (on purpose), the session cookie is not.
    if ($continue) {
        Show-Header "1. CSRF token"
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        try {
            # The token is read from the BODY of /api/v1/auth/csrf. A script doesn't have as
            # convenient a cookie jar as a browser, and the body carries exactly the same value
            # as the cookie - that's what AmorcageCsrfTest guarantees.
            $amorce = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/csrf" -WebSession $session -TimeoutSec 5
            $jeton = $amorce.token
            if ($jeton) {
                Show-Ok "CSRF token received (expected header: $($amorce.headerName))."
            } else {
                Show-Fail "No CSRF token in the response."
                Show-Hint "Without it, every POST will answer 403 with no clear message - trap #1 of the mechanism."
                $continue = $false
            }
        } catch {
            Show-Fail "Could not get a CSRF token: $($_.Exception.Message)"
            $continue = $false
        }
    }

    # --- 2. Login ---
    if ($continue) {
        Show-Header "2. Login"
        try {
            $corps = @{ email = $Utilisateur; password = $MotDePasse } | ConvertTo-Json
            $reponse = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post `
                -Body $corps -ContentType "application/json" `
                -Headers @{ "X-XSRF-TOKEN" = $jeton } -WebSession $session -TimeoutSec 10
            $moi = $reponse
            Show-Ok "Logged in as $Utilisateur."
            Show-Info "accountId: $($moi.accountId)"
            foreach ($cabinet in $moi.clinics) {
                Show-Info "clinic $($cabinet.clinicId) -> $($cabinet.roles -join ', ')"
            }
            $cookieSession = ($session.Cookies.GetCookies($BaseUrl) | Where-Object { $_.Name -eq "CLINEXA_SESSION" }).Value
            if ($cookieSession) {
                Show-Ok "CLINEXA_SESSION session cookie received (opaque: no rights inside)."
            } else {
                Show-Fail "No session cookie."
                $continue = $false
            }
        } catch {
            Show-Fail "Login refused: $($_.Exception.Message)"
            Show-Hint "Is the dev profile active? Accounts are only provisioned by it (SEC-05)."
            Show-Hint "Start identity-service with -Dspring.profiles.active=dev"
            $continue = $false
        }
    }

    # --- 3. Authenticated route ---
    if ($continue) {
        Show-Header "3. Authenticated route"
        try {
            $relu = Invoke-RestMethod -Uri "$BaseUrl/api/v1/me" -WebSession $session -TimeoutSec 5
            if ($relu.accountId -eq $moi.accountId) {
                Show-Ok "/api/v1/me read with the session cookie."
            } else {
                Show-Fail "/api/v1/me returns a different account."
            }
        } catch {
            Show-Fail "/api/v1/me refused: $($_.Exception.Message)"
        }
    }

    # --- 4. The same session on care-service ---
    # Proof that the session is actually shared (Redis), and that the gateway is not a
    # trust boundary: each service resolves the session itself.
    if ($continue) {
        Show-Header "4. The same session on care-service"
        $cabinetPraticien = ($moi.clinics | Where-Object { $_.roles -contains "PRACTITIONER" } | Select-Object -First 1)
        if (-not $cabinetPraticien) {
            Show-Skip "This account is not a PRACTITIONER anywhere: nothing to read on the care side."
        } else {
            try {
                $url = "$BaseUrlCare/api/v1/clinics/$($cabinetPraticien.clinicId)/records/00000000-0000-0000-0000-000000000000/clinical"
                Invoke-RestMethod -Uri $url -WebSession $session -TimeoutSec 5 | Out-Null
                Show-Ok "care-service accepted the session (nonexistent record, so 404 expected)."
            } catch {
                $code = $_.Exception.Response.StatusCode.value__
                if ($code -eq 404) {
                    Show-Ok "care-service accepted the session (404: the record doesn't exist, that's expected)."
                } elseif ($code -eq 401) {
                    Show-Fail "care-service answers 401: the session isn't shared."
                    Show-Hint "Check that both services point at the SAME Redis and the same spring.session.redis.namespace."
                } elseif ($code -eq 403) {
                    Show-Fail "care-service answers 403: session read, but rights refused."
                    Show-Hint "Is identity-service reachable? Unreachable = deliberate 403 (SEC-10, criterion 12)."
                } else {
                    Show-Fail "care-service answers $code."
                }
            }
        }
    }

    # --- 5. Logout ---
    if ($continue) {
        Show-Header "5. Logout"
        try {
            $jetonCourant = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/csrf" -WebSession $session -TimeoutSec 5).token
            Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/logout" -Method Post `
                -Headers @{ "X-XSRF-TOKEN" = $jetonCourant } -WebSession $session -TimeoutSec 5 | Out-Null
            Show-Ok "Logged out."
        } catch {
            Show-Fail "Logout refused: $($_.Exception.Message)"
        }

        try {
            Invoke-RestMethod -Uri "$BaseUrl/api/v1/me" -WebSession $session -TimeoutSec 5 | Out-Null
            Show-Fail "The session still works after logging out."
            Show-Hint "An opaque session must stop being worth anything as soon as it's destroyed."
        } catch {
            if ($_.Exception.Response.StatusCode.value__ -eq 401) {
                Show-Ok "The session is worth nothing anymore (401) - immediate, server-side revocation."
            } else {
                Show-Fail "Expected 401 after logout, got $($_.Exception.Response.StatusCode.value__)."
            }
        }
    }
}
finally {
    Show-Header "Result"
    if ($script:AllPassed -and $continue) {
        Write-Host "  All green. Development session is working." -ForegroundColor Green
        exit 0
    } else {
        Write-Host "  At least one step failed." -ForegroundColor Red
        exit 1
    }
}
