# GIF Recording Specifications for Marketplace & README

## Required GIFs for Marketplace Listing

| # | Feature | Tool | Duration | Description |
|---|---|---|---|---|
| 1 | Universal Test Runner | `rider_test` | 15-20s | Run tests in different IDEs (Rider, PyCharm, GoLand, WebStorm) showing auto-detection |
| 2 | Workspace Config | `rider_workspace_config` | 10-15s | Show per-IDE build outputs, run configs, IDE-specific extensions |
| 3 | DB Console Execute | `rider_execute_console` | 15-20s | Execute SQL with GO batches, async jobs, CSV export |
| 4 | Cargo Actions | `rider_cargo` | 15-20s | Show test/check/clippy/fmt with workspace/target filtering |
| 5 | DB Console Results | `rider_console_results` | 10s | Read results from user-run scripts |
| 6 | Test Runner (Rider) | `rider_tests` | 10-15s | Run scoped tests, structured results, rerun failed |
| 7 | Tool Window Reading | `rider_tool_window` | 10s | Read Build/Problems/Debug/Run consoles |
| 8 | IDE State | `rider_get_ide_state` | 5s | Show indexing/busy status |

---

## Recording Specifications

### Technical Requirements
| Parameter | Value |
|---|---|
| Format | GIF (for README) + MP4 (for Marketplace) |
| Resolution | 1920x1080 minimum |
| Frame rate | 10-15 fps (GIF), 30 fps (MP4) |
| Max duration | 20 seconds each |
| File size | < 10 MB per GIF |
| Background | Dark theme preferred |

### Recording Environment
- **OS**: macOS (for consistent look) or Linux
- **IDE**: Rider 2026.2+ (primary), RustRover, PyCharm, GoLand, WebStorm, IntelliJ IDEA
- **Theme**: Darcula (dark)
- **Font**: JetBrains Mono, 14pt
- **Window size**: 1920x1080 recording area

### Test Projects Needed
| IDE | Project Type | Location |
|---|---|---|
| Rider | .NET solution with tests | `test-projects/rider-dotnet/` |
| RustRover | Cargo workspace | `test-projects/rust-cargo/` |
| PyCharm | Python pytest project | `test-projects/pycharm-pytest/` |
| GoLand | Go module with tests | `test-projects/goland-go/` |
| WebStorm | npm/vitest project | `test-projects/webstorm-js/` |
| IntelliJ | Maven/Gradle project | `test-projects/idea-java/` |
| CLion | CMake project | `test-projects/clion-cpp/` |
| PhpStorm | Composer/PHPUnit project | `test-projects/phpstorm-php/` |
| RubyMine | Bundler/RSpec project | `test-projects/rubymine-ruby/` |

---

## Recording Scripts (Manual Process)

### GIF 1: Universal Test Runner (`rider_test`)
```bash
# In each IDE:
1. Open test project
2. Run: rider_test(action="run", filter="MyTest")
2. Wait for completion
3. Run: rider_test(action="coverage")
4. Run: rider_test(action="rerun_failed")
```

### GIF 2: Workspace Config (`rider_workspace_config`)
```bash
# In each IDE:
1. Open project with build outputs
2. Run: rider_workspace_config()
3. Show IDE-specific extensions
```

### GIF 3: DB Console Execute (`rider_execute_console`)
```bash
# In Rider/any IDE with DB:
1. Open DB console
2. Execute multi-batch SQL with GO
4. Show async job with progress
5. Export to CSV
```

### GIF 4: Cargo Actions (`rider_cargo`)
```bash
# In RustRover:
1. Open Cargo workspace
2. Run: rider_cargo(action="test", pkg="my-crate", testTargets="true")
3. Run: rider_cargo(action="clippy", fix=true)
4. Run: rider_cargo(action="check", workspace=false, pkg="my-crate")
```

---

## Automation Helper (Future)

```bash
# Potential automation using IDE's built-in recording
# Requires: IDE running, plugin loaded, test projects open

# Rider built-in recording:
# View → Tool Windows → Screen Recording (if available)
# Or: macOS built-in: Cmd+Shift+5 → Record Selected Portion → Save as GIF

# Linux: peek, byzanz-record, gifski
# macOS: Kap, Gifski, built-in screen recording → convert to GIF
```

---

## README Placeholders (Update After Recording)

```markdown
## Universal Test Runner
![rider_test](assets/gifs/rider_test.gif)

## Workspace Configuration
![rider_workspace_config](assets/gifs/rider_workspace_config.gif)

## Database Console
![rider_execute_console](assets/gifs/rider_execute_console.gif)

## Cargo Management
![rider_cargo](assets/gifs/rider_cargo.gif)
```

---

## Directory Structure
```
assets/
├── gifs/
│   ├── rider_test.gif
│   ├── rider_workspace_config.gif
│   ├── rider_execute_console.gif
│   ├── rider_cargo.gif
│   ├── rider_console_results.gif
│   ├── rider_tests.gif
│   ├── rider_tool_window.gif
│   └── rider_get_ide_state.gif
└── screenshots/
    ├── marketplace_1.png
    ├── marketplace_2.png
    └── marketplace_3.png
```

---

## Next Steps
1. [ ] Set up test projects in each IDE
2. [ ] Record GIFs (manual process - ~2 hours)
3. [ ] Convert to GIF + optimize (gifski/gifsicle)
4. [ ] Add to `assets/gifs/`
5. [ ] Update README.md with GIF embeds
6. [ ] Update Marketplace listing with screenshots
7. [ ] Update TODO.md