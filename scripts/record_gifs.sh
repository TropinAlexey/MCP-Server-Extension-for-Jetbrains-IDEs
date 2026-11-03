#!/bin/bash
# GIF Recording Helper Script
# Usage: ./scripts/record_gifs.sh [gif_name]
# Records a GIF using macOS built-in screen recording

set -e

ASSETS_DIR="assets/gifs"
mkdir -p "$ASSETS_DIR"

show_help() {
    echo "GIF Recording Helper"
    echo "Usage: $0 [gif_name]"
    echo ""
    echo "Available GIFs:"
    echo "  rider_test              - Universal test runner"
    echo "  rider_workspace_config  - Workspace configuration"
    echo "  rider_execute_console   - DB console execute"
    echo "  rider_cargo             - Cargo management"
    echo "  rider_console_results   - DB console results"
    echo "  rider_tests             - Rider test runner"
    echo "  rider_tool_window       - Tool window reading"
    echo "  rider_get_ide_state     - IDE state"
    echo ""
    echo "Or run without args to see all specs."
}

if [ $# -eq 0 ]; then
    show_help
    exit 0
fi

GIF_NAME="$1"
OUTPUT_FILE="$ASSETS_DIR/${GIF_NAME}.gif"

case "$GIF_NAME" in
    rider_test)
        echo "Recording: Universal Test Runner"
        echo "1. Open test project in Rider/PyCharm/GoLand/WebStorm"
        echo "2. Run: rider_test(action=\"run\", filter=\"MyTest\")"
        echo "3. Run: rider_test(action=\"coverage\")"
        echo "4. Run: rider_test(action=\"rerun_failed\")"
        ;;
    rider_workspace_config)
        echo "Recording: Workspace Config"
        echo "1. Open project with build outputs in each IDE"
        echo "2. Run: rider_workspace_config()"
        echo "3. Show IDE-specific extensions"
        ;;
    rider_execute_console)
        echo "Recording: DB Console Execute"
        echo "1. Open DB console in Rider"
        echo "2. Execute multi-batch SQL with GO"
        echo "3. Show async job with progress"
        echo "4. Export to CSV"
        ;;
    rider_cargo)
        echo "Recording: Cargo Actions"
        echo "1. Open Cargo workspace in RustRover"
        echo "2. Run: rider_cargo(action=\"test\", pkg=\"my-crate\", testTargets=\"true\")"
        echo "3. Run: rider_cargo(action=\"clippy\", fix=true)"
        echo "4. Run: rider_cargo(action=\"check\", workspace=false, pkg=\"my-crate\")"
        ;;
    rider_console_results)
        echo "Recording: DB Console Results"
        echo "1. User runs script in DB console"
        echo "2. Run: rider_console_results()"
        echo "3. Show result grids + output log"
        ;;
    rider_tests)
        echo "Recording: Rider Test Runner"
        echo "1. Open .NET solution in Rider"
        echo "2. Run: rider_tests(action=\"run\", filter=\"MyTest\")"
        echo "3. Show structured results"
        echo "4. Run: rider_tests(action=\"rerun_failed\")"
        ;;
    rider_tool_window)
        echo "Recording: Tool Window Reading"
        echo "1. Open Build/Problems/Debug/Run tool windows"
        echo "2. Run: rider_tool_window(action=\"content\", windowId=\"Debug\", section=\"console\")"
        ;;
    rider_get_ide_state)
        echo "Recording: IDE State"
        echo "1. Run: rider_get_ide_state()"
        echo "2. Show indexing/busy status"
        ;;
    *)
        echo "Unknown GIF: $GIF_NAME"
        show_help
        exit 1
        ;;
esac

echo ""
echo "Recording instructions:"
echo "1. macOS: Cmd+Shift+5 → Record Selected Portion → Save as GIF"
echo "2. Linux: peek, byzanz-record, or gifski"
echo "3. Save to: $OUTPUT_FILE"
echo ""
echo "Recommended settings:"
echo "  - Resolution: 1920x1080"
echo "  - Duration: 15-20 seconds max"
echo "  - Frame rate: 10-15 fps (GIF)"
echo "  - Theme: Darcula (dark)"
echo "  - Font: JetBrains Mono, 14pt"