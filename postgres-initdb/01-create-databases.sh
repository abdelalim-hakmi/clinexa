#!/usr/bin/env bash
# ============================================================================
# Clinexa — PostgreSQL first - startup initialization
# ============================================================================
#
# Strategy: idempotent, not "run-once-and-forget".
#
# The Postgres entrypoint only executes scripts under /docker-entrypoint-initdb.d/
# when PGDATA is empty (i.e. the `postgres` named volume is being created for the
# first time) — see docker-compose.yaml. In that sense this script already only
# ever runs once per volume. But relying on that guarantee is fragile: a wiped
# volume, a restore from an old snapshot, or a manual re-run of this file against
# a live database would all replay these statements. Writing it idempotent costs
# nothing up front and means it's safe to re-run by hand at any time.
#
# Note: Postgres has no `CREATE DATABASE IF NOT EXISTS` / `CREATE USER IF NOT
# EXISTS` shorthand (that's a MySQL-ism). Idempotence here is done properly:
#   - the app_user role is created inside a DO block that checks pg_roles first
#   - the database is created via the classic psql `\gexec` idiom, since
#     CREATE DATABASE cannot run inside a transaction block (which a DO block
#     implicitly is)
#
# This is a .sh script (not .sql) specifically so it can read POSTGRES_PASSWORD
# from the environment — the Postgres entrypoint only shell-expands *.sh
# scripts under docker-entrypoint-initdb.d, plain *.sql files are run as-is
# with no variable substitution. app_user's password is kept in sync with
# POSTGRES_PASSWORD from .env this way, with no hardcoded duplicate.
#
# Convention for new databases (one per microservice): add a new numbered
# script here (02-create-db-<service>.sh, 03-..., scripts run in alphabetical
# order) rather than editing this file — each script owns exactly one
# database + its GRANT. The app_user role stays defined once, here.
# ============================================================================
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	DO \$\$
	BEGIN
	    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_user') THEN
	        CREATE ROLE app_user LOGIN PASSWORD '${POSTGRES_PASSWORD}';
	    END IF;
	END
	\$\$;

	SELECT 'CREATE DATABASE clinexa_test OWNER app_user'
	WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'clinexa_test')
	\gexec

	GRANT ALL PRIVILEGES ON DATABASE clinexa_test TO app_user;
EOSQL
