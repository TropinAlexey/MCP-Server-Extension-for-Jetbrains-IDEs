# Release & Publish Process

## Quick Reference

| Step | Command | Notes |
|------|---------|-------|
| 1. Build & Verify | `./gradlew check buildPlugin` | Runs all checks + plugin verifier |
| 2. Version Bump | Edit `gradle.properties` | Update `version = X.Y.Z` |
| 3. Changelog | `./gradlew generateChangelog` | Creates `CHANGELOG.md` |
| 4. Update Docs | Edit `README.md`, `CHANGELOG.html`, `TODO.md` | Sync version, add entry |
| 5. Commit | `git add -A && git commit -m "vX.Y.Z: ..."` | Include all changes |
| 6. Tag | `git tag vX.Y.Z` | Matches gradle.properties |
| 6. Push | `git push && git push --tags` | Triggers CI publish |

## CI/CD Pipeline (GitHub Actions)

The `.github/workflows/build.yml` handles publishing:

```yaml
- name: Publish to Marketplace
  if: github.ref == 'refs/tags/v*' && github.event_name == 'push'
  env:
    PUBLISH_TOKEN: ${{ secrets.PUBLISH_TOKEN }}
  run: ./gradlew publishPlugin
```

**Required Secret:** `PUBLISH_TOKEN` in GitHub repository settings
- Create at: https://plugins.jetbrains.com/author/me/tokens
- Add to: GitHub repo → Settings → Secrets → Actions → `PUBLISH_TOKEN`

## Version Scheme

- `vX.Y.Z` tags → triggers CI publish
- `gradle.properties` version MUST match tag (without `v`)
- Minor bump for features, patch for fixes, major for breaking changes

## Plugin Verifier Checklist

Run locally before tagging:
```bash
./gradlew verifyPlugin
```

Must show: `Compatible` (internal API warnings OK, but no compatibility errors)

## Current Version

Check `gradle.properties`:
```properties
version = 2.11.0
```

## Release Artifacts

- Plugin ZIP: `build/distributions/mcp-server-extension-<version>.zip`
- CHANGELOG.md (generated)
- CHANGELOG.html (manual, for Marketplace)
- README.md (auto-synced via checkToolDocs)

## Checklist Before Release

- [ ] All checks pass: `./gradlew check`
- [ ] Plugin Verifier: `./gradlew verifyPlugin` → Compatible
- [ ] Version bumped in `gradle.properties`
- [ ] `CHANGELOG.md` generated via `./gradlew generateChangelog`
- [ ] `CHANGELOG.html` updated with new entry
- [ ] `README.md` version references updated
- [ ] `TODO.md` items checked off
- [ ] `git diff --stat` shows only expected changes
- [ ] Commit message follows: `vX.Y.Z: Brief summary`
- [ ] Tag pushed: `git push && git push --tags`

## CI/CD Notes

- Pipeline runs on: push to main, PRs, and tags
- `verifyPlugin` runs on every PR and push
- Publish only runs on version tags (`v*`)
- Uses `PUBLISH_TOKEN` from GitHub secrets
- If CI fails: check `PUBLISH_TOKEN` secret is valid and not expired

## Emergency Rollback

If a bad release is published:
1. Go to Marketplace plugin page
2. Click "Unpublish" on the bad version
3. Fix issue, bump patch version, re-release

---

**Last Updated:** 2026-11-01  
**Current Version:** 2.11.0  
**Next Version:** 2.12.0 (or 2.11.1 for patches)