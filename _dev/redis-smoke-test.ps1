<#
    Smoke test Redis (single-node dev) - projet clinexa
    Valide, dans l'ordre : daemon Docker -> container -> serveur pret (PING)
    -> SET -> GET (round-trip) -> acces cross-container (reseau Docker interne).
    Nettoie systematiquement la cle de test creee, meme en cas d'echec plus haut.

    Usage : .\redis-smoke-test.ps1
#>

param(
    [string]$ContainerName = "clinexa_redis",
    [string]$Network       = "",   # si vide, auto-detecte depuis le container (voir etape 1bis)
    [string]$Image         = "redis:8.0-alpine",
    [string]$DbPort        = "6379",
    [string]$Password      = "clinexa"
)

$KeyName = "smoke-test:$(Get-Date -Format 'yyyyMMddHHmmss')"
$script:AllPassed = $true
$script:KeySet     = $false
$continue          = $true
$pingOk            = $false

function Show-Header($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Show-Ok($m)     { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Show-Fail($m)   { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:AllPassed = $false }
function Show-Hint($m)   { Write-Host "         -> $m" -ForegroundColor DarkYellow }
function Show-Skip($m)   { Write-Host "  [SKIP] $m" -ForegroundColor DarkGray }

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

    # --- 2. Serveur pret (PING) ---
    Show-Header "2. Serveur Redis - PING"
    if ($continue) {
        $pong = docker exec $ContainerName redis-cli --no-auth-warning -a $Password PING
        if ($LASTEXITCODE -ne 0 -or ($pong -join '') -notmatch 'PONG') {
            Show-Fail "Redis ne repond pas (PING)."
            Show-Hint "Le container tourne mais le process Redis a peut-etre plante ou n'a pas fini de demarrer. Regarde : docker logs $ContainerName --tail 50"
            $continue = $false
        } else {
            $pingOk = $true
            Show-Ok "Serveur pret et accepte les connexions."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }

    # --- 3. SET (ecriture de test) ---
    Show-Header "3. SET (cle de test : $KeyName)"
    $testMessage = "smoke-test-payload-$([guid]::NewGuid())"
    if ($pingOk) {
        docker exec $ContainerName redis-cli --no-auth-warning -a $Password SET $KeyName $testMessage | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Impossible d'ecrire la cle '$KeyName'."
            Show-Hint "Verifie REDIS_PASSWORD et que le serveur accepte les writes (docker logs $ContainerName)."
        } else {
            $script:KeySet = $true
            Show-Ok "Cle ecrite."
        }
    } else {
        Show-Skip "Ignore (serveur non pret)."
    }

    # --- 4. GET + verification du round-trip ---
    Show-Header "4. GET (round-trip)"
    if ($script:KeySet) {
        $got = docker exec $ContainerName redis-cli --no-auth-warning -a $Password GET $KeyName
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Erreur en relisant la cle '$KeyName'."
            Show-Hint "Le serveur accepte peut-etre les writes mais pas les reads - verifie 'docker logs $ContainerName'."
        }
        elseif (($got -join "`n") -notmatch [regex]::Escape($testMessage)) {
            Show-Fail "La valeur relue ne correspond pas a celle ecrite."
            Show-Hint "Attendu : $testMessage / Recu : $got"
        } else {
            Show-Ok "Cle relue et contenu verifie (round-trip OK)."
        }
    } else {
        Show-Skip "Ignore (cle non ecrite)."
    }

    # --- 5. Acces cross-container (reseau Docker interne) ---
    Show-Header "5. Acces cross-container ($ContainerName`:$DbPort sur '$Network')"
    if ($continue) {
        docker run --rm --network $Network $Image redis-cli --no-auth-warning -h $ContainerName -p $DbPort -a $Password PING | Out-Null
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
    # --- 6. Nettoyage (toujours execute, meme si un test a echoue) ---
    Show-Header "6. Nettoyage"
    if ($script:KeySet) {
        docker exec $ContainerName redis-cli --no-auth-warning -a $Password DEL $KeyName | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Show-Ok "Cle '$KeyName' supprimee."
        } else {
            Show-Fail "Echec de la suppression de la cle '$KeyName' - a nettoyer manuellement :"
            Show-Hint "docker exec $ContainerName redis-cli -a $Password DEL $KeyName"
        }
    } else {
        Show-Skip "Aucune cle ecrite, rien a nettoyer."
    }

    Write-Host ""
    if ($script:AllPassed) {
        Write-Host "=== RESULTAT : TOUS LES TESTS SONT PASSES ===" -ForegroundColor Green
    } else {
        Write-Host "=== RESULTAT : AU MOINS UN TEST A ECHOUE (voir [FAIL] ci-dessus) ===" -ForegroundColor Red
    }
}

if (-not $script:AllPassed) { exit 1 }
exit 0
