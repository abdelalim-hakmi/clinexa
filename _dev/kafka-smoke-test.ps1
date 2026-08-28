<#
    Smoke test Kafka (KRaft, single-node dev) - projet clinexa
    Valide, dans l'ordre : daemon Docker -> container -> broker (listener host)
    -> creation topic -> election du leader -> produce/consume (round-trip)
    -> listener DOCKER (acces cross-container).
    Nettoie systematiquement le topic de test cree, meme en cas d'echec plus haut.

    Usage : .\kafka-smoke-test.ps1
    Voir aussi : Tech Notes/kafka-cluster.md (section Smoke tests)
#>

param(
    [string]$ContainerName  = "clinexa_kafka",
    [string]$Network        = "",   # si vide, auto-detecte depuis le container (voir etape 1bis)
    [string]$Image          = "confluentinc/cp-kafka:7.6.0",
    [string]$BootstrapHost  = "localhost:9092",
    [string]$DockerListener = "clinexa_kafka:29092",
    [switch]$NonInteractive  # saute la pause manuelle de l'etape 6bis (pour un run scripte/CI)
)

$TopicName = "smoke-test-$(Get-Date -Format 'yyyyMMddHHmmss')"
$script:AllPassed    = $true
$script:TopicCreated = $false
$continue            = $true
$topicOk             = $false

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

    # --- 2. Broker repond (listener host) ---
    Show-Header "2. Broker Kafka - listener host ($BootstrapHost)"
    if ($continue) {
        docker exec $ContainerName kafka-broker-api-versions --bootstrap-server $BootstrapHost | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Kafka ne repond pas sur $BootstrapHost."
            Show-Hint "Le container tourne mais le process Kafka a peut-etre plante ou n'a pas fini de demarrer. Regarde : docker logs $ContainerName --tail 50"
            $continue = $false
        } else {
            Show-Ok "Broker repond sur $BootstrapHost."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }

    # --- 3. Creation du topic de test ---
    Show-Header "3. Creation du topic de test ($TopicName)"
    if ($continue) {
        docker exec $ContainerName kafka-topics --create --topic $TopicName --bootstrap-server $BootstrapHost --partitions 1 --replication-factor 1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Impossible de creer le topic '$TopicName'."
            Show-Hint "Verifie KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR / KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR (doivent etre <= nombre de brokers)."
        } else {
            $script:TopicCreated = $true
            $topicOk = $true
            Show-Ok "Topic cree."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }

    # --- 4. Election du leader ---
    Show-Header "4. Election du leader"
    if ($topicOk) {
        $describe = docker exec $ContainerName kafka-topics --describe --topic $TopicName --bootstrap-server $BootstrapHost
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($describe)) {
            Show-Fail "Impossible de decrire le topic '$TopicName'."
        }
        elseif (($describe -join ' ') -match 'Leader:\s*(-?\d+)') {
            $leaderId = $Matches[1]
            if ($leaderId -eq '-1') {
                Show-Fail "Aucun leader elu pour la partition (Leader: -1)."
                Show-Hint "Probleme de quorum/controller KRaft (pas un probleme reseau) - verifie KAFKA_CONTROLLER_QUORUM_VOTERS."
            } else {
                Show-Ok "Leader elu (broker $leaderId)."
            }
        } else {
            Show-Fail "Impossible de determiner le leader depuis la sortie de 'describe'."
            Show-Hint "Sortie brute : $describe"
        }
    } else {
        Show-Skip "Ignore (topic non cree)."
    }

    # --- 5. Produce (non-interactif) ---
    Show-Header "5. Produce (message de test)"
    $testMessage = "smoke-test-payload-$([guid]::NewGuid())"
    if ($topicOk) {
        $testMessage | docker exec -i $ContainerName kafka-console-producer --topic $TopicName --bootstrap-server $BootstrapHost | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Echec de la production du message."
            Show-Hint "Verifie que le broker accepte les writes (docker logs $ContainerName)."
        } else {
            Show-Ok "Message produit."
        }
    } else {
        Show-Skip "Ignore (topic non cree)."
    }

    # --- 6. Consume + verification du round-trip ---
    Show-Header "6. Consume (round-trip)"
    if ($topicOk) {
        $consumed = docker exec $ContainerName kafka-console-consumer --topic $TopicName --bootstrap-server $BootstrapHost --from-beginning --max-messages 1 --timeout-ms 10000
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Timeout (10s) ou erreur en consommant le message."
            Show-Hint "Le broker accepte peut-etre les writes mais pas les reads - verifie 'docker logs $ContainerName'."
        }
        elseif (($consumed -join "`n") -notmatch [regex]::Escape($testMessage)) {
            Show-Fail "Le message relu ne correspond pas a celui produit."
            Show-Hint "Attendu : $testMessage / Recu : $consumed"
        } else {
            Show-Ok "Message relu et contenu verifie (round-trip OK)."
        }
    } else {
        Show-Skip "Ignore (topic non cree)."
    }

    # --- 6bis. Pause manuelle : laisse le temps d'aller verifier dans Kafka UI avant la suppression du topic ---
    if ($topicOk -and -not $NonInteractive) {
        Write-Host ""
        Read-Host "Check the Kafka UI (http://localhost:8080) to see if you can see the event - press Enter to continue"
    }

    # --- 7. Listener DOCKER (acces cross-container) ---
    Show-Header "7. Listener DOCKER - acces cross-container ($DockerListener)"
    if ($continue) {
        docker run --rm --network $Network $Image kafka-broker-api-versions --bootstrap-server $DockerListener | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Show-Fail "Le listener DOCKER ($DockerListener) ne repond pas depuis un autre container."
            Show-Hint "Verifie KAFKA_ADVERTISED_LISTENERS et que le reseau '$Network' est correct (voir kafka-cluster.md, section 2.2)."
        } else {
            Show-Ok "Listener DOCKER accessible depuis un autre container sur '$Network'."
        }
    } else {
        Show-Skip "Ignore (blocage precedent)."
    }
}
finally {
    # --- 8. Nettoyage (toujours execute, meme si un test a echoue) ---
    Show-Header "8. Nettoyage"
    if ($script:TopicCreated) {
        docker exec $ContainerName kafka-topics --delete --topic $TopicName --bootstrap-server $BootstrapHost | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Show-Ok "Topic '$TopicName' supprime."
        } else {
            Show-Fail "Echec de la suppression du topic '$TopicName' - a nettoyer manuellement :"
            Show-Hint "docker exec $ContainerName kafka-topics --delete --topic $TopicName --bootstrap-server $BootstrapHost"
        }
    } else {
        Show-Skip "Aucun topic cree, rien a nettoyer."
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
