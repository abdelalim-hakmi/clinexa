<#
    Smoke test Zipkin (single-node dev) - projet clinexa
    Valide, dans l'ordre : daemon Docker -> container -> serveur pret (UI)
    -> POST span de test -> GET trace (round-trip) -> acces cross-container
    (reseau Docker interne).
    Stockage in-memory : rien a nettoyer cote serveur (perdu au redemarrage).

    Usage : .\zipkin-smoke-test.ps1
#>

param(
    [string]$ContainerName = "clinexa_zipkin",
    [string]$Network       = "",   # si vide, auto-detecte depuis le container (voir etape 1bis)
    [string]$Image         = "curlimages/curl:8.11.1",   # image utilitaire pour le test cross-container (zipkin lui-meme n'a ni curl ni wget)
    [string]$DbPort        = "9411"
)

$script:AllPassed = $true
$script:TempDir   = $null
$continue         = $true
$serverOk         = $false
$TraceId          = -join ((1..16) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$SpanId           = -join ((1..16) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$SpanName         = "smoke-test-$(Get-Date -Format 'yyyyMMddHHmmss')"

function Show-Header($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Show-Ok($m)     { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Show-Fail($m)   { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:AllPassed = $false }
function Show-Hint($m)   { Write-Host "         -> $m" -ForegroundColor DarkYellow }
function Show-Skip($m)   { Write-Host "  [SKIP] $m" -ForegroundColor DarkGray }

# Le JSON du span est ecrit dans un fichier temporaire puis monte dans le container curl :
# passer un gros JSON en argument direct de "docker run" via PowerShell se fait tronquer
# silencieusement par le marshaling d'arguments natif (teste et confirme).
function New-SpanFile {
    $script:TempDir = Join-Path ([System.IO.Path]::GetTempPath()) "clinexa-zipkin-smoke-$PID"
    New-Item -ItemType Directory -Path $script:TempDir -Force | Out-Null
    $nowMicros = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000
    $spanJson = "[{`"traceId`":`"$TraceId`",`"id`":`"$SpanId`",`"name`":`"$SpanName`",`"timestamp`":$nowMicros,`"duration`":1000,`"localEndpoint`":{`"serviceName`":`"clinexa-smoke-test`"}}]"
    $spanFilePath = Join-Path $script:TempDir "span.json"
    [System.IO.File]::WriteAllText($spanFilePath, $spanJson, [System.Text.Encoding]::ASCII)
    return $spanFilePath
}

try {
    # --- 0. Docker daemon ---
    Show-Header "0. Docker daemon"
    docker info | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Show-Fail "Docker daemon inaccessible."
        Show-Hint "Lance Docker Desktop, attends qu'il soit pret, puis relance ce script."
        $continue = $false
    } else {
        Show-Ok "Docker daemon accessible."
    }

    # --- 1. Container present et demarre ---
    Show-Header "1. Container '$ContainerName'"
    if ($continue) {
        $containerStatus = docker ps -a --filter "name=^$ContainerName`$" --format "{{.Status}}"
        if ([string]::IsNullOrWhiteSpace($containerStatus)) {
            Show-Fail "Le container '$ContainerName' n'existe pas."
            Show-Hint "Lance 'docker compose up -d' depuis le dossier du projet."
            $continue = $false
        }
        elseif ($containerStatus -notmatch '^Up') {
            Show-Fail "Le container '$ContainerName' existe mais n'est pas demarre (status: $containerStatus)."
            Show-Hint "Lance 'docker start $ContainerName' ou 'docker compose up -d'."
            $continue = $false
        }
        else {
            Show-Ok "Container demarre (status: $containerStatus)."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }

    # --- 1bis. Detection du reseau reel (Compose prefixe le nom du projet, ex: clinexa-dev-net -> clinexa_clinexa-dev-net) ---
    Show-Header "1bis. Detection du reseau Docker reel"
    if ($continue) {
        if ([string]::IsNullOrWhiteSpace($Network)) {
            $networksJson = docker inspect $ContainerName --format '{{json .NetworkSettings.Networks}}'
            if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($networksJson)) {
                $networksObj = $networksJson | ConvertFrom-Json
                $firstNetwork = $networksObj.PSObject.Properties | Select-Object -First 1
                if ($firstNetwork) {
                    $Network = $firstNetwork.Name
                    Show-Ok "Reseau detecte : '$Network'."
                } else {
                    Show-Fail "Le container n'est rattache a aucun reseau Docker."
                    $continue = $false
                }
            } else {
                Show-Fail "Impossible d'inspecter les reseaux du container '$ContainerName'."
                $continue = $false
            }
        } else {
            Show-Ok "Reseau fourni en parametre : '$Network'."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }

    # --- 2. Serveur pret (UI) ---
    Show-Header "2. Serveur Zipkin - UI"
    if ($continue) {
        docker run --rm --network $Network $Image curl -sf "http://${ContainerName}:${DbPort}/zipkin/" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Zipkin ne repond pas (UI)."
            Show-Hint "Le container tourne mais le process Zipkin a peut-etre plante ou n'a pas fini de demarrer. Regarde : docker logs $ContainerName --tail 50"
            $continue = $false
        } else {
            $serverOk = $true
            Show-Ok "Serveur pret et accepte les connexions."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }

    # --- 3. POST (span de test) ---
    Show-Header "3. POST /api/v2/spans (trace : $TraceId)"
    if ($serverOk) {
        $spanFilePath = New-SpanFile
        docker run --rm --network $Network -v "${script:TempDir}:/smoke:ro" $Image curl -sf -X POST "http://${ContainerName}:${DbPort}/api/v2/spans" -H "Content-Type: application/json" --data-binary "@/smoke/span.json" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Impossible d'envoyer le span de test."
            Show-Hint "Verifie que le serveur accepte les writes (docker logs $ContainerName)."
        } else {
            Show-Ok "Span envoye."
        }
    } else {
        Show-Skip "Ignore (serveur non pret)."
    }

    # --- 4. GET + verification du round-trip ---
    Show-Header "4. GET /api/v2/trace/{traceId} (round-trip)"
    if ($serverOk) {
        Start-Sleep -Seconds 1  # laisse le temps a l'ingestion in-memory de s'appliquer
        $got = docker run --rm --network $Network $Image curl -sf "http://${ContainerName}:${DbPort}/api/v2/trace/${TraceId}"
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Erreur en relisant la trace '$TraceId'."
            Show-Hint "Le serveur accepte peut-etre les writes mais pas les reads - verifie 'docker logs $ContainerName'."
        }
        elseif (($got -join "`n") -notmatch [regex]::Escape($SpanName)) {
            Show-Fail "La trace relue ne correspond pas au span envoye."
            Show-Hint "Attendu le span : $SpanName / Recu : $got"
        } else {
            Show-Ok "Trace relue et contenu verifie (round-trip OK)."
        }
    } else {
        Show-Skip "Ignore (serveur non pret)."
    }

    # --- 5. Acces cross-container (reseau Docker interne) ---
    Show-Header "5. Acces cross-container ($ContainerName`:$DbPort sur '$Network')"
    if ($continue) {
        docker run --rm --network $Network $Image curl -sf "http://${ContainerName}:${DbPort}/zipkin/" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Le serveur n'est pas joignable depuis un autre container sur '$Network'."
            Show-Hint "Verifie que le reseau '$Network' est correct et que le container '$ContainerName' y est bien rattache."
        } else {
            Show-Ok "Serveur accessible depuis un autre container sur '$Network'."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }
}
finally {
    # --- 6. Nettoyage ---
    Show-Header "6. Nettoyage"
    if ($script:TempDir -and (Test-Path $script:TempDir)) {
        Remove-Item -Recurse -Force $script:TempDir
        Show-Ok "Fichier temporaire local supprime."
    }
    Show-Skip "Stockage in-memory : rien a nettoyer cote serveur, la trace de test disparait au redemarrage du container."

    Write-Host ""
    if ($script:AllPassed) {
        Write-Host "=== RESULTAT : TOUS LES TESTS SONT PASSES ===" -ForegroundColor Green
    } else {
        Write-Host "=== RESULTAT : AU MOINS UN TEST A ECHOUE (voir [FAIL] ci-dessus) ===" -ForegroundColor Red
    }
}

if (-not $script:AllPassed) { exit 1 }
exit 0
