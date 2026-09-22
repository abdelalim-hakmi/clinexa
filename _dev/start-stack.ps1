<#
    Ordered start of the Clinexa stack - development machine only.

    WHY THIS SCRIPT EXISTS. The services are not interchangeable at startup: one that boots
    before the Config Server answers gets no configuration at all, silently falls back to
    port 8080 and then fails on something unrelated-looking. The order below is the one in
    CLAUDE.md, and every step here WAITS for the previous service to actually answer rather
    than sleeping a fixed number of seconds - a fixed sleep is always too short on a cold
    machine and too long on a warm one.

        docker infra -> config-server -> discovery-server -> identity-service (dev)
                     -> care-service -> api-gateway -> routing through the gateway

    INTELLIJ IS THE ACTUAL SUPERVISOR. This script does not spawn its own processes anymore.
    It writes one IntelliJ run configuration per service under .idea/runConfigurations/ (see
    -NoIdeaConfigs), then - in order, waiting for each to answer before moving on - clicks
    "Run" on each one FOR you, through the "Run Configuration Bridge" plugin's local REST API.
    Every service therefore starts as a real IntelliJ-launched process: its console is a tab in
    IntelliJ's Run tool window, it shows up in the Services tool window, and you stop or restart
    it from there like anything else you started by hand. Re-running this script skips whatever
    is already answering.

    ONE-TIME SETUP THIS RELIES ON:
      1. Open this project in IntelliJ IDEA 2026.2 (the Maven import must have already happened,
         so the run configurations' <module name="..."> resolves).
      2. Settings > Plugins > Marketplace > install "Run Configuration Bridge"
         (https://plugins.jetbrains.com/plugin/30842-run-configuration-bridge) and restart the IDE.
         It auto-starts a REST server on localhost:9877 with the IDE - nothing else to configure.
         The free tier (list / run / stop configurations, process listing) is all this script uses.
      Without that plugin reachable, use -DirectLaunch as a fallback: it starts each service in
      its own plain PowerShell window instead, the way this script used to work - no IntelliJ
      integration, but no plugin dependency either.

    IntelliJ starts everything in parallel from a Compound; this script exists because the stack
    needs an order, so no Compound is generated.

    Usage:
      .\start-stack.ps1                      # full ordered start, via IntelliJ (Run Configuration Bridge)
      .\start-stack.ps1 -SkipDocker          # containers already up
      .\start-stack.ps1 -Only care-service   # (re)start one service, respecting its probe
      .\start-stack.ps1 -Stop                # stop the five services (containers left alone)
      .\start-stack.ps1 -NoIdeaConfigs       # don't touch .idea/runConfigurations/
      .\start-stack.ps1 -DirectLaunch        # fallback: plain PowerShell windows, no IntelliJ
      .\start-stack.ps1 -BridgePort 9878     # Run Configuration Bridge listens on a non-default port
#>

[CmdletBinding()]
param(
    [switch]$SkipDocker,
    [switch]$NoIdeaConfigs,
    [switch]$Force,                       # overwrite existing IntelliJ run configurations
    [switch]$Stop,
    [switch]$DirectLaunch,                # bypass IntelliJ: start services in plain PowerShell windows
    [string[]]$Only,
    [int]$TimeoutSec       = 180,         # per service, cold JVM + Liquibase on a slow disk
    [int]$DockerTimeoutSec = 120,
    [int]$BridgePort       = 9877,        # Run Configuration Bridge plugin's REST port (auto-probes a few above it too)
    [string]$BridgeToken   = '',          # only needed if the plugin's optional Bearer-token auth is turned on
    [string]$JavaHome,                    # override the JDK 25 auto-detection
    [string]$SmokeUser     = 'erin@clinexa.ma',   # dev fixture, used only for the readiness check
    [string]$SmokePassword = 'Clinexa!2026'       # provisioned by the dev profile (SEC-05)
)

$ErrorActionPreference = 'Continue'
# Not 'Stop': every native call below (mvn, docker) is checked explicitly via $LASTEXITCODE,
# and under 'Stop' a native command's own stderr chatter - e.g. JDK 25's harmless
# "WARNING: A restricted method in java.lang.System has been called" on every mvn invocation,
# or docker compose's routine progress lines - gets wrapped into a terminating
# NativeCommandError and aborts the whole script before the real exit-code check ever runs.

# ---------------------------------------------------------------------------------------------
# Output helpers - same vocabulary as session-smoke-test.ps1, so the two read alike.
# ---------------------------------------------------------------------------------------------
function Show-Header($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Show-Ok($m)     { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Show-Fail($m)   { Write-Host "  [FAIL] $m" -ForegroundColor Red }
function Show-Skip($m)   { Write-Host "  [SKIP] $m" -ForegroundColor DarkGray }
function Show-Hint($m)   { Write-Host "         -> $m" -ForegroundColor DarkYellow }
function Show-Info($m)   { Write-Host "         $m" -ForegroundColor Gray }

$RepoRoot  = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $RepoRoot 'server'

# ---------------------------------------------------------------------------------------------
# The stack, in start order. `Probe` is what "this service is up" actually means for it:
# config-server and discovery-server carry no actuator, so they are asked for the thing they
# exist to serve instead of a health endpoint that isn't there. `Name` doubles as the IntelliJ
# run configuration's name (plain, no numbering) - it has to stay unique and stable whether you
# start the full stack or a single service via -Only, which it already is.
# ---------------------------------------------------------------------------------------------
$Services = @(
    [pscustomobject]@{
        Name = 'config-server'    ; Module = 'platform/config-server'
        Main = 'com.clinexa.config.ConfigServerApplication'
        Port = 8888 ; Probe = 'http://localhost:8888/application/default' ; Profiles = ''
        Why  = 'Serves every other service its configuration. Nothing starts correctly before it.'
    }
    [pscustomobject]@{
        Name = 'discovery-server' ; Module = 'platform/discovery-server'
        Main = 'com.clinexa.discovery.DiscoveryServerApplication'
        Port = 8761 ; Probe = 'http://localhost:8761/eureka/apps' ; Profiles = ''
        Why  = 'Eureka. The gateway routes lb://<name>, which resolves here.'
    }
    [pscustomobject]@{
        Name = 'identity-service' ; Module = 'services/identity-service'
        Main = 'com.clinexa.identity.IdentityServiceApplication'
        Port = 8100 ; Probe = 'http://localhost:8100/actuator/health' ; Profiles = 'dev'
        Why  = 'Issues the session, and answers /internal/.../assignments for every tenant route (SEC-10).'
    }
    [pscustomobject]@{
        Name = 'care-service'     ; Module = 'services/care-service'
        Main = 'com.clinexa.care.CareServiceApplication'
        Port = 8101 ; Probe = 'http://localhost:8101/actuator/health' ; Profiles = ''
        Why  = 'Depends on identity-service at request time: unreachable = deliberate 403 (criterion 12).'
    }
    [pscustomobject]@{
        Name = 'api-gateway'      ; Module = 'platform/api-gateway'
        Main = 'com.clinexa.apigateway.ApiGatewayApplication'
        Port = 9000 ; Probe = 'http://localhost:9000/actuator/health' ; Profiles = ''
        Why  = 'Last on purpose: it can only route services already registered in Eureka.'
    }
)

if ($Only) {
    $unknown = $Only | Where-Object { $_ -notin $Services.Name }
    if ($unknown) { Show-Fail "Unknown service(s): $($unknown -join ', ')"; exit 1 }
    $Services = $Services | Where-Object { $_.Name -in $Only }
}

# ---------------------------------------------------------------------------------------------
# Probing. One HTTP call, short timeout, no exception noise: "does it answer" is the question,
# not "what did it answer".
# ---------------------------------------------------------------------------------------------
function Test-Probe([string]$Url) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
        return $r.StatusCode -ge 200 -and $r.StatusCode -lt 400
    } catch { return $false }
}

# The wait the fixed sleep can never get right. When $Process is given (only true in
# -DirectLaunch mode, where this script owns the process) it also watches it: a service that
# died during boot fails here in a second instead of holding the script for the full timeout.
# In IntelliJ/Bridge mode $Process is always $null - the process belongs to the IDE, and the
# free tier of the plugin doesn't expose liveness - so a dead boot is only caught by the timeout.
function Wait-Until([string]$Url, [int]$Seconds, [System.Diagnostics.Process]$Process, [string]$Label) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $spin = '|/-\'
    $i = 0
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        if (Test-Probe $Url) { Write-Host "`r                                                            `r" -NoNewline; return $true }
        if ($Process -and $Process.HasExited) {
            Write-Host "`r                                                            `r" -NoNewline
            Show-Fail "$Label exited during startup (code $($Process.ExitCode))."
            Show-Hint "Its window stayed open: read the stack trace there."
            return $false
        }
        Write-Host ("`r         {0} waiting for {1}  {2}s" -f $spin[$i], $Label, [int]$sw.Elapsed.TotalSeconds) -NoNewline
        $i = ($i + 1) % 4
        Start-Sleep -Milliseconds 700
    }
    Write-Host "`r" -NoNewline
    Show-Fail "$Label did not answer within $Seconds s."
    return $false
}

# ---------------------------------------------------------------------------------------------
# Run Configuration Bridge client. One local REST call, short timeout, JSON in/out. Response
# shapes are read defensively (array vs. {configurations:[...]}, id vs. processId) because the
# plugin is versioned independently of this script; if a future version renames a field, this is
# the only place that needs to change.
# ---------------------------------------------------------------------------------------------
function Invoke-Bridge([int]$Port, [string]$Method, [string]$Path) {
    $headers = @{}
    if ($BridgeToken) { $headers['Authorization'] = "Bearer $BridgeToken" }
    # -ErrorAction Stop: under this script's global 'Continue', Invoke-RestMethod reports a non-2xx
    # response as a non-terminating error - it prints, then execution falls through to the NEXT
    # statement as if nothing happened. Forcing it terminating here is what lets Invoke-BridgeRun's
    # try/catch below actually see the failure instead of silently starting a Wait-Until that can
    # never succeed (the actual failure mode hit at api-gateway: a 429 was reported, then the
    # script carried on to wait 180s for a service that was never started).
    return Invoke-RestMethod -Uri "http://localhost:$Port$Path" -Method $Method -Headers $headers -TimeoutSec 10 -ErrorAction Stop
}

# Extracts the {success,data,error,code} body from a failed Invoke-Bridge call - Windows
# PowerShell 5.1 doesn't parse a non-2xx response body automatically, so it's read off the raw
# HttpWebResponse by hand.
function Get-BridgeErrorBody($ErrorRecord) {
    $resp = $ErrorRecord.Exception.Response
    if (-not $resp) { return $null }
    try {
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        return ($reader.ReadToEnd() | ConvertFrom-Json)
    } catch { return $null }
}

# POSTs .../run with retry: the Bridge plugin caps concurrent IntelliJ-tracked processes (default
# 5, Settings > Tools > Run Configuration Bridge) and counts EVERYTHING the IDE is running, not
# just this stack's own Spring Boot processes - including this very script if it was itself
# launched through an IntelliJ Run button rather than a plain terminal. That cap can free up on
# its own (a leftover process finishing, a previous run of this script timing out), so a
# transient CONCURRENT_PROCESS_LIMIT is retried for a while before it's treated as fatal.
function Invoke-BridgeRun([int]$Port, [string]$ConfigId, [string]$Label) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $attempt = 0
    while ($true) {
        $attempt++
        try {
            Invoke-Bridge -Port $Port -Method POST -Path "/api/v1/configurations/$ConfigId/run" | Out-Null
            return
        } catch {
            $body = Get-BridgeErrorBody $_
            if ($body -and $body.code -eq 'CONCURRENT_PROCESS_LIMIT' -and $sw.Elapsed.TotalSeconds -lt 60) {
                Show-Info "Bridge is at its concurrent-process limit - waiting for a slot to free up (attempt $attempt)..."
                Start-Sleep -Seconds 5
                continue
            }
            $msg = if ($body -and $body.error) { $body.error } else { $_.Exception.Message }
            Show-Fail "$Label - IntelliJ refused to start it via the Bridge: $msg"
            if ($body -and $body.code -eq 'CONCURRENT_PROCESS_LIMIT') {
                Show-Hint "Settings > Tools > Run Configuration Bridge > raise 'Max concurrent processes' above 5 -"
                Show-Hint "it counts every process the IDE runs, this stack's five services included."
                Show-Hint "Or run this script from a plain terminal instead of IntelliJ's own Run button:"
                Show-Hint "that keeps the script's own process off the Bridge's slot count."
            }
            exit 1
        }
    }
}

# Probes $BridgePort and a few ports above it (the plugin auto-increments past 9877 if that
# port is taken - e.g. two IntelliJ windows open) and returns the first that answers.
function Resolve-BridgePort {
    param([switch]$Quiet)
    foreach ($p in $BridgePort..($BridgePort + 5)) {
        try { Invoke-Bridge -Port $p -Method GET -Path '/api/status' | Out-Null; return $p }
        catch { continue }
    }
    if (-not $Quiet) {
        Show-Fail "Run Configuration Bridge not reachable on :$BridgePort..$($BridgePort + 5)."
        Show-Hint "Open this project in IntelliJ IDEA with the plugin installed and running:"
        Show-Hint "  Settings > Plugins > Marketplace > 'Run Configuration Bridge' > Install > restart IDE."
        Show-Hint "It auto-starts with the IDE (Settings > Tools > Run Configuration Bridge to change its port)."
        Show-Hint "Or run with -DirectLaunch to skip IntelliJ entirely (plain PowerShell windows, no plugin needed)."
    }
    return $null
}

# Every Run Configuration Bridge response is {success, data, error, code} (confirmed against
# v2.2.0: /api/status, /api/v1/configurations and /api/v1/processes all wrap this way) - unwrap
# .data, falling back to the raw response for a future version that stops wrapping.
function Get-BridgeList($Response) {
    if ($Response -and ($Response.PSObject.Properties.Name -contains 'data')) { return @($Response.data) }
    return @($Response)
}

# ---------------------------------------------------------------------------------------------
# -Stop: the five JVMs of this stack, wherever they came from. Tried first through the Bridge
# (a clean stop IntelliJ's UI agrees with, so it doesn't keep showing a "running" tab for a
# process that was actually killed underneath it), then swept for by matching java.exe on its
# main class - the only way to catch services started with -DirectLaunch, or a Bridge that
# wasn't reachable. Containers are left running on purpose; they are cheap and losing their
# state costs more than it saves.
# ---------------------------------------------------------------------------------------------
if ($Stop) {
    Show-Header "Stopping the Clinexa services"
    $stoppedViaBridge = @()

    $bridgePort = Resolve-BridgePort -Quiet
    if ($bridgePort) {
        try {
            $procs = Get-BridgeList (Invoke-Bridge -Port $bridgePort -Method GET -Path '/api/v1/processes')
            foreach ($p in $procs) {
                # confirmed shape (Bridge v2.2.0): {processId, configurationId, configurationName, status, ...}
                $cfgName = if ($p.PSObject.Properties.Name -contains 'configurationName') { $p.configurationName } else { '' }
                $match = $Services | Where-Object { $cfgName -eq $_.Name } | Select-Object -First 1
                if (-not $match) { continue }
                if ($p.status -and $p.status -ne 'RUNNING') { continue }
                $pid_ = if ($p.PSObject.Properties.Name -contains 'processId') { $p.processId } else { $p.id }
                try {
                    Invoke-Bridge -Port $bridgePort -Method POST -Path "/api/v1/processes/$pid_/stop" | Out-Null
                    Show-Ok "$($match.Name) stopped via IntelliJ (Run Configuration Bridge)."
                    $stoppedViaBridge += $match.Name
                } catch { Show-Fail "$($match.Name) via Bridge: $($_.Exception.Message)" }
            }
        } catch { Show-Info "Run Configuration Bridge not reachable - falling back to a process scan." }
    }

    $mains = $Services.Main
    $found = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $cl = $_.CommandLine; $cl -and ($mains | Where-Object { $cl -like "*$_*" }) }

    if (-not $found -and -not $stoppedViaBridge) { Show-Skip "No service of this stack is running." }
    foreach ($p in $found) {
        $svc = ($Services | Where-Object { $p.CommandLine -like "*$($_.Main)*" } | Select-Object -First 1).Name
        if ($stoppedViaBridge -contains $svc) { continue }
        try {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop
            Show-Ok "$svc stopped (pid $($p.ProcessId))."
        } catch { Show-Fail "$svc (pid $($p.ProcessId)): $($_.Exception.Message)" }
    }
    Show-Info "Containers left running. Stop them with: docker compose stop"
    exit 0
}

# ---------------------------------------------------------------------------------------------
# 0. The JDK. This is the single most common way this stack fails to start on a machine that
#    has more than one JDK: JAVA_HOME points at an older one and Maven stops on
#    "release version 25 not supported". The JDK is resolved here and passed to each child
#    process, WITHOUT touching the machine's own JAVA_HOME. Needed even in IntelliJ/Bridge mode:
#    step 2 below still builds `shared` with the CLI Maven, and -DirectLaunch needs it directly.
# ---------------------------------------------------------------------------------------------
Show-Header "0. Toolchain"

if (-not $JavaHome) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += Get-ChildItem 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium' `
                        -Directory -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -match '(^|[^0-9])25([^0-9]|$)' } |
                   Sort-Object Name -Descending | ForEach-Object { $_.FullName }

    # The version is read from the JDK's own `release` file rather than by running `java -version`:
    # that command writes to stderr, and capturing stderr from a native process is exactly the
    # thing Windows PowerShell 5.1 turns into a spurious error.
    foreach ($c in $candidates) {
        if (-not (Test-Path (Join-Path $c 'bin\java.exe'))) { continue }
        $releaseFile = Join-Path $c 'release'
        if (-not (Test-Path $releaseFile)) { continue }
        $line = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="([0-9]+)' -ErrorAction SilentlyContinue |
                Select-Object -First 1
        if ($line -and [int]$line.Matches[0].Groups[1].Value -ge 25) { $JavaHome = $c; break }
    }
}

if (-not $JavaHome) {
    Show-Fail "No JDK 25+ found."
    Show-Hint "Install it, or pass -JavaHome 'C:\Program Files\Java\jdk-25.x.y'."
    exit 1
}
Show-Ok "JDK: $JavaHome"
if ($env:JAVA_HOME -and $env:JAVA_HOME -ne $JavaHome) {
    Show-Info "JAVA_HOME here is $env:JAVA_HOME - it is overridden only while this script builds,"
    Show-Info "and restored afterwards. Each -DirectLaunch window gets the JDK above explicitly."
}

$mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue
$mvn = if ($mvnCommand) { $mvnCommand.Source } else { $null }
if (-not $mvn) { Show-Fail "mvn is not on PATH."; Show-Hint "Add Maven to PATH, or use IntelliJ's bundled Maven."; exit 1 }
Show-Ok "Maven: $mvn"

$shell = if (Get-Command pwsh -ErrorAction SilentlyContinue) { 'pwsh' } else { 'powershell' }

# ---------------------------------------------------------------------------------------------
# 1. Infrastructure. Postgres, Redis and Kafka all declare a healthcheck, so "up" here means
#    healthy, not "the container exists". A service started against a Postgres still
#    initialising fails on Liquibase in a way that reads like a schema bug.
# ---------------------------------------------------------------------------------------------
if (-not $SkipDocker) {
    Show-Header "1. Docker infrastructure"
    try {
        docker info --format '{{.ServerVersion}}' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "docker unreachable" }
    } catch {
        Show-Fail "Docker does not answer. Start Docker Desktop."
        exit 1
    }

    Push-Location $RepoRoot
    try {
        docker compose up -d | Out-Null
        if ($LASTEXITCODE -ne 0) { Show-Fail "docker compose up failed. Run it by hand to see why."; exit 1 }
    } finally { Pop-Location }

    foreach ($c in 'clinexa_postgres', 'clinexa_redis', 'clinexa_kafka') {
        $state = docker inspect -f '{{.State.Health.Status}}' $c 2>$null
        if ($LASTEXITCODE -ne 0) {
            Show-Skip "$c is not part of the active COMPOSE_PROFILES."
            continue
        }
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while ($state -ne 'healthy' -and $sw.Elapsed.TotalSeconds -lt $DockerTimeoutSec) {
            Start-Sleep -Seconds 2
            $state = docker inspect -f '{{.State.Health.Status}}' $c 2>$null
        }
        if ($state -eq 'healthy') { Show-Ok "$c healthy." }
        else { Show-Fail "$c is '$state' after $DockerTimeoutSec s."; Show-Hint "docker logs $c"; exit 1 }
    }
} else {
    Show-Header "1. Docker infrastructure"
    Show-Skip "-SkipDocker"
}

# ---------------------------------------------------------------------------------------------
# 2. shared, installed once. Each service resolves it from the local Maven repository. In
#    IntelliJ/Bridge mode the IDE's own "Make" (enabled on every generated run configuration)
#    also rebuilds it as a project module dependency before each run - this step is what keeps
#    the CLI-Maven view (session-smoke-test.ps1, a plain `mvn test`) in sync with that.
# ---------------------------------------------------------------------------------------------
Show-Header "2. shared module"
# A .ps1 runs INSIDE your session, so setting JAVA_HOME here would outlive the script.
# It is set for the length of the build and put back, whatever happens.
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    & $mvn -q -ntp -pl shared -am install -DskipTests -f (Join-Path $ServerDir 'pom.xml')
    $buildFailed = ($LASTEXITCODE -ne 0)
} finally {
    if ($null -eq $previousJavaHome) { Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue }
    else { $env:JAVA_HOME = $previousJavaHome }
}
if ($buildFailed) { Show-Fail "Installing 'shared' failed."; exit 1 }
Show-Ok "shared installed (security primitives + test-jar)."

# ---------------------------------------------------------------------------------------------
# 3. IntelliJ run configurations - written before anything is started, because Bridge mode
#    looks services up by this exact name (plain, e.g. "care-service" - no numbering: the
#    service names are already unique, and IntelliJ sorts its Run list alphabetically anyway).
#    Grouped under the "Clinexa" folder so they're easy to tell apart from IntelliJ's own
#    auto-detected Spring Boot configurations (e.g. "CareServiceApplication"). Not regenerated
#    for a service already answering (nothing to start), and not overwritten unless -Force.
# ---------------------------------------------------------------------------------------------
if (-not $NoIdeaConfigs) {
    Show-Header "3. IntelliJ run configurations"
    $dir = Join-Path $RepoRoot '.idea\runConfigurations'
    New-Item -ItemType Directory -Path $dir -Force | Out-Null

    foreach ($s in $Services) {
        $file = Join-Path $dir ("Clinexa_{0}.xml" -f ($s.Name -replace '-', '_'))

        if ((Test-Path $file) -and -not $Force) { Show-Skip "$($s.Name) (already there; -Force to overwrite)"; continue }

        $profileLine = if ($s.Profiles) { "`n    <option name=`"ACTIVE_PROFILES`" value=`"$($s.Profiles)`" />" } else { '' }
        $xml = @"
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="$($s.Name)" type="SpringBootApplicationConfigurationType" factoryName="Spring Boot" folderName="Clinexa" nameIsGenerated="false">
    <module name="$($s.Name)" />
    <option name="SPRING_BOOT_MAIN_CLASS" value="$($s.Main)" />$profileLine
    <method v="2">
      <option name="Make" enabled="true" />
    </method>
  </configuration>
</component>
"@
        # UTF-8 without BOM: Set-Content -Encoding utf8 writes a BOM on Windows PowerShell 5.1.
        [System.IO.File]::WriteAllText($file, $xml, (New-Object System.Text.UTF8Encoding $false))
        Show-Ok $s.Name
    }
    Show-Info "If IntelliJ shows them in red, its module names differ: fix <module name> in the XML."
    Show-Info "No Compound is generated on purpose - IntelliJ starts a compound in parallel, and this stack needs an order."
} else {
    Show-Header "3. IntelliJ run configurations"
    Show-Skip "-NoIdeaConfigs"
}

# ---------------------------------------------------------------------------------------------
# 4. The services, in order - started BY INTELLIJ, through the Run Configuration Bridge plugin's
#    REST API: this is "click Run" done for you, on the run configuration written above, so the
#    console, the process and the stop/restart controls all live in IntelliJ afterwards.
#    -DirectLaunch bypasses all of this and goes back to a plain PowerShell window per service.
# ---------------------------------------------------------------------------------------------
Show-Header "4. Services"

if ($DirectLaunch) {
    Show-Info "-DirectLaunch: starting services in plain PowerShell windows (no IntelliJ integration)."
    foreach ($s in $Services) {
        if (Test-Probe $s.Probe) {
            Show-Skip "$($s.Name) already answering on :$($s.Port) - left as it is."
            continue
        }

        # Single quotes only inside: Start-Process wraps this whole string in double quotes when it
        # hands it to the child shell, and a double quote in here would end that wrapping early.
        $profileArg = if ($s.Profiles) { " '-Dspring-boot.run.profiles=$($s.Profiles)'" } else { '' }
        $inner = "`$Host.UI.RawUI.WindowTitle = 'clinexa | $($s.Name) | :$($s.Port)'; " +
                 "`$env:JAVA_HOME = '$JavaHome'; " +
                 "& '$mvn' -ntp spring-boot:run$profileArg"

        $proc = Start-Process -FilePath $shell `
                              -ArgumentList '-NoExit', '-NoProfile', '-Command', $inner `
                              -WorkingDirectory (Join-Path $ServerDir $s.Module) `
                              -PassThru

        Write-Host "  [..]   $($s.Name) starting (pid $($proc.Id)) - $($s.Why)" -ForegroundColor DarkCyan

        if (-not (Wait-Until $s.Probe $TimeoutSec $proc $s.Name)) {
            Show-Hint "Its window is still open with the full log."
            exit 1
        }
        Show-Ok "$($s.Name) up on :$($s.Port)."
    }
} else {
    $bridgePort = Resolve-BridgePort
    if (-not $bridgePort) { exit 1 }
    Show-Ok "Run Configuration Bridge reachable on :$bridgePort"

    foreach ($s in $Services) {
        if (Test-Probe $s.Probe) {
            Show-Skip "$($s.Name) already answering on :$($s.Port) - left as it is."
            continue
        }

        # IntelliJ's VFS watcher notices a freshly (re)written .idea/runConfigurations/*.xml with a
        # short lag - the Bridge only knows about a configuration once RunManager has reloaded it.
        # Poll instead of a single lookup, so a config this same run just wrote isn't a false FAIL.
        $cfgId = $null
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        while ($sw.Elapsed.TotalSeconds -lt 20) {
            try {
                $configs = Get-BridgeList (Invoke-Bridge -Port $bridgePort -Method GET -Path '/api/v1/configurations')
                $found = $configs | Where-Object { $_.name -eq $s.Name } | Select-Object -First 1
                if ($found) { $cfgId = if ($found.PSObject.Properties.Name -contains 'id') { $found.id } else { $found.configurationId }; break }
            } catch { } # transient - the retry loop below just tries again
            Start-Sleep -Milliseconds 800
        }
        if (-not $cfgId) {
            Show-Fail "No IntelliJ run configuration named '$($s.Name)' (Bridge still doesn't see it after 20s)."
            Show-Hint "Re-run without -NoIdeaConfigs (add -Force if it already exists but looks stale),"
            Show-Hint "then make sure IntelliJ finished re-importing the Maven project."
            exit 1
        }

        Invoke-BridgeRun -Port $bridgePort -ConfigId $cfgId -Label $s.Name
        Write-Host "  [..]   $($s.Name) starting via IntelliJ - $($s.Why)" -ForegroundColor DarkCyan

        if (-not (Wait-Until $s.Probe $TimeoutSec $null $s.Name)) {
            Show-Hint "Open its tab in IntelliJ's Run (or Services) tool window for the stack trace."
            exit 1
        }
        Show-Ok "$($s.Name) up on :$($s.Port)."
    }
}

# ---------------------------------------------------------------------------------------------
# 5. The steps that actually prove the stack is USABLE, which "every port answers" does not.
#
#    Two distinct Eureka propagations have to happen, and both take up to ~30 s after the
#    services themselves are up:
#      a. the gateway learns where identity-service is, or it answers 503;
#      b. care-service learns it too - and until it does, EVERY tenant route answers
#         403 AUTH_ASSIGNMENTS_UNAVAILABLE. That refusal is correct (SEC-10 fail-closed,
#         criterion 12), which is exactly why it must not be mistaken for a broken stack.
#
#    (b) is checked with the dev fixtures this script itself provisions - the only way to
#    exercise the care -> identity call is to make a real authenticated request.
# ---------------------------------------------------------------------------------------------
function Wait-TenantRouteReady([int]$Seconds, [string]$User, [string]$Password) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $lastSeen = 'no answer yet'
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        try {
            $web = New-Object Microsoft.PowerShell.Commands.WebRequestSession
            $token = (Invoke-RestMethod -Uri 'http://localhost:8100/api/v1/auth/csrf' -WebSession $web -TimeoutSec 5).token
            $me = Invoke-RestMethod -Uri 'http://localhost:8100/api/v1/auth/login' -Method Post `
                    -Body (@{ email = $User; password = $Password } | ConvertTo-Json) `
                    -ContentType 'application/json' -Headers @{ 'X-XSRF-TOKEN' = $token } `
                    -WebSession $web -TimeoutSec 10
            $clinic = ($me.clinics | Where-Object { $_.roles -contains 'PRACTITIONER' } | Select-Object -First 1).clinicId
            if (-not $clinic) { return @($true, 'no PRACTITIONER fixture to check with') }

            $url = "http://localhost:8101/api/v1/clinics/$clinic/records/00000000-0000-0000-0000-000000000000/clinical"
            try {
                Invoke-WebRequest -Uri $url -WebSession $web -UseBasicParsing -TimeoutSec 5 | Out-Null
                return @($true, 'answered 2xx')
            } catch {
                $code = $_.Exception.Response.StatusCode.value__
                # 404 is the WANTED answer: the route was authorised and @TenantId filtered the
                # nonexistent record. 403 means assignments are still unreachable - keep waiting.
                if ($code -eq 404) { return @($true, '404 on a nonexistent record - the tenant chain is live') }
                $lastSeen = "HTTP $code"
            }
        } catch {
            $lastSeen = 'identity-service not answering the login yet'
        }
        Start-Sleep -Seconds 3
    }
    return @($false, $lastSeen)
}

if (-not $Only -or $Only -contains 'api-gateway' -or $Only -contains 'care-service') {
    Show-Header "5. End-to-end readiness"

    if (Wait-Until 'http://localhost:9000/api/v1/auth/csrf' 90 $null 'gateway -> identity-service') {
        Show-Ok "The gateway routes to identity-service."
    } else {
        Show-Fail "The gateway still does not route after 90 s."
        Show-Hint "Check http://localhost:8761 : are identity-service and care-service registered?"
    }

    Write-Host "         checking care-service -> identity-service (SEC-10)..." -ForegroundColor Gray
    $ready, $detail = Wait-TenantRouteReady 120 $SmokeUser $SmokePassword
    if ($ready) {
        Show-Ok "Tenant routes are live ($detail)."
    } else {
        Show-Fail "A tenant route still refuses after 120 s (last: $detail)."
        Show-Hint "403 here means care-service cannot reach identity-service: that is the"
        Show-Hint "designed fail-closed of SEC-10, not a bug. Check Eureka and care-service's window."
    }
}

# ---------------------------------------------------------------------------------------------
Show-Header "Stack"
foreach ($s in $Services) {
    $state = if (Test-Probe $s.Probe) { 'up  ' } else { 'DOWN' }
    Write-Host ("  {0}  {1,-18} :{2}" -f $state, $s.Name, $s.Port) -ForegroundColor ($(if ($state -eq 'up  ') { 'Green' } else { 'Red' }))
}
Write-Host ""
Show-Info "Eureka   http://localhost:8761      Zipkin  http://localhost:9411"
if (-not $DirectLaunch) {
    Show-Info "Each service runs as an IntelliJ process: see its console in the Run (or Services) tool window."
    Show-Info "Stop or restart one from there, or stop everything with:  .\_dev\start-stack.ps1 -Stop"
}
Show-Info "Verify the session end to end:  .\_dev\session-smoke-test.ps1"
Show-Info "Stop everything:                .\_dev\start-stack.ps1 -Stop"
