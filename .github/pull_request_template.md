## What and why

<!-- One or two sentences. Link the vault story / ADR if there is one. -->

## Checklist

- [ ] Tests added or updated for the change (including the ❌ cases for any new route)
- [ ] `mvn clean verify` / `yarn lint && yarn format:check && yarn test --watch=false` green locally
- [ ] New route? `_docs/security/authorization-matrix.md` and `matrice.csv` updated **first**
- [ ] Docs updated (`README`, `_docs/`) if behaviour or setup changed
- [ ] No secret, credential or real patient data in the diff
