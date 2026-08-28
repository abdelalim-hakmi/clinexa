<#
    Smoke test MongoDB (single-node dev) - projet clinexa
    Valide, dans l'ordre : daemon Docker -> container -> serveur pret (ping)
    -> creation collection -> insert -> find (round-trip) -> acces cross-container
    (reseau Docker interne).
    Nettoie systematiquement la collection de test creee, meme en cas d'echec plus haut.

    Usage : .\mongo-smoke-test.ps1
#>

param(
    [string]$ContainerName = "clinexa_mongo",
    [string]$Network       = "",   # si vide, auto-detecte depuis le container (voir etape 1bis)
    [string]$Image         = "mongo:8.0",
    [string]$DbPort        = "27017",
    [string]$User          = "clinexa",
    [string]$Password      = "clinexa",
    [string]$Database      = "clinexa"
)

$CollectionName = "smoke_test_$(Get-Date -Format 'yyyyMMddHHmmss')"
$script:AllPassed        = $true
$script:CollectionCreated = $false
$continue                = $true
$collectionOk            = $false

function Show-Header($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Show-Ok($m)     { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Show-Fail($m)   { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:AllPassed = $false }
function Show-Hint($m)   { Write-Host "         -> $m" -ForegroundColor DarkYellow }
function Show-Skip($m)   { Write-Host "  [SKIP] $m" -ForegroundColor DarkGray }

function Get-MongoUri($host_) { "mongodb://${User}:${Password}@${host_}:$DbPort/${Database}?authSource=admin" }

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

    # --- 2. Serveur pret (ping) ---
    Show-Header "2. Serveur Mongo - ping"
    if ($continue) {
        docker exec $ContainerName mongosh (Get-MongoUri "localhost") --quiet --eval "db.adminCommand('ping')" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Mongo ne repond pas (ping)."
            Show-Hint "Le container tourne mais le process Mongo a peut-etre plante ou n'a pas fini de demarrer. Regarde : docker logs $ContainerName --tail 50"
            $continue = $false
        } else {
            Show-Ok "Serveur pret et accepte les connexions."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }

    # --- 3. Creation de la collection de test ---
    Show-Header "3. Creation de la collection de test ($CollectionName)"
    if ($continue) {
        docker exec $ContainerName mongosh (Get-MongoUri "localhost") --quiet --eval "db.createCollection('$CollectionName')" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Impossible de creer la collection '$CollectionName'."
            Show-Hint "Verifie MONGO_USER / MONGO_PASSWORD."
        } else {
            $script:CollectionCreated = $true
            $collectionOk = $true
            Show-Ok "Collection creee."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }

    # --- 4. Insert (non-interactif) ---
    Show-Header "4. Insert (document de test)"
    $testMessage = "smoke-test-payload-$([guid]::NewGuid())"
    if ($collectionOk) {
        docker exec $ContainerName mongosh (Get-MongoUri "localhost") --quiet --eval "db.$CollectionName.insertOne({ payload: '$testMessage' })" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Echec de l'insertion du document de test."
            Show-Hint "Verifie que le serveur accepte les writes (docker logs $ContainerName)."
        } else {
            Show-Ok "Document insere."
        }
    } else {
        Show-Skip "Ignore (collection non creee)."
    }

    # --- 5. Find + verification du round-trip ---
    Show-Header "5. Find (round-trip)"
    if ($collectionOk) {
        $found = docker exec $ContainerName mongosh (Get-MongoUri "localhost") --quiet --eval "print(db.$CollectionName.findOne({ payload: '$testMessage' }).payload)"
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Erreur en relisant le document insere."
            Show-Hint "Le serveur accepte peut-etre les writes mais pas les reads - verifie 'docker logs $ContainerName'."
        }
        elseif (($found -join "`n") -notmatch [regex]::Escape($testMessage)) {
            Show-Fail "Le document relu ne correspond pas a celui insere."
            Show-Hint "Attendu : $testMessage / Recu : $found"
        } else {
            Show-Ok "Document relu et contenu verifie (round-trip OK)."
        }
    } else {
        Show-Skip "Ignore (collection non creee)."
    }

    # --- 6. Acces cross-container (reseau Docker interne) ---
    Show-Header "6. Acces cross-container ($ContainerName`:$DbPort sur '$Network')"
    if ($continue) {
        docker run --rm --network $Network $Image mongosh (Get-MongoUri $ContainerName) --quiet --eval "db.adminCommand('ping')" | Out-Null
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
    # --- 7. Nettoyage (toujours execute, meme si un test a echoue) ---
    Show-Header "7. Nettoyage"
    if ($script:CollectionCreated) {
        docker exec $ContainerName mongosh (Get-MongoUri "localhost") --quiet --eval "db.$CollectionName.drop()" | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Show-Ok "Collection '$CollectionName' supprimee."
        } else {
            Show-Fail "Echec de la suppression de la collection '$CollectionName' - a nettoyer manuellement :"
            Show-Hint "docker exec $ContainerName mongosh `"$(Get-MongoUri 'localhost')`" --eval `"db.$CollectionName.drop()`""
        }
    } else {
        Show-Skip "Aucune collection creee, rien a nettoyer."
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
