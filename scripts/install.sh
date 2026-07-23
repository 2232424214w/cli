#!/usr/bin/env bash
# Install BetterCLI as a global `bettercli` command (Claude Code style).
#
# Layout:
#   ~/.bettercli/bin/bettercli
#   ~/.bettercli/lib/bettercli.jar
#
# Usage:
#   ./scripts/install.sh           # package if needed, then install
#   ./scripts/install.sh --skip-build
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_ROOT="${BETTERCLI_HOME:-$HOME/.bettercli}"
BIN_DIR="$INSTALL_ROOT/bin"
LIB_DIR="$INSTALL_ROOT/lib"
JAR_NAME="bettercli-1.0-SNAPSHOT.jar"
SRC_JAR="$ROOT/target/$JAR_NAME"
SKIP_BUILD=0

for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    -h|--help)
      echo "Usage: $0 [--skip-build]"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

if ! command -v java >/dev/null 2>&1; then
  echo "❌ Java not found. BetterCLI requires Java 17+." >&2
  exit 1
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "❌ Maven not found. Install Maven, or pass --skip-build with an existing jar." >&2
    exit 1
  fi
  echo "📦 Packaging BetterCLI..."
  (cd "$ROOT" && mvn -q clean package -DskipTests)
fi

if [[ ! -f "$SRC_JAR" ]]; then
  echo "❌ Missing jar: $SRC_JAR" >&2
  echo "   Run: mvn clean package" >&2
  exit 1
fi

mkdir -p "$BIN_DIR" "$LIB_DIR"
cp "$SRC_JAR" "$LIB_DIR/bettercli.jar"
cp "$ROOT/scripts/bettercli" "$BIN_DIR/bettercli"
chmod +x "$BIN_DIR/bettercli"

# Also drop a Windows-friendly launcher for WSL/Git Bash users sharing the home dir.
cp "$ROOT/scripts/bettercli.cmd" "$BIN_DIR/bettercli.cmd"

echo "✅ Installed:"
echo "   $BIN_DIR/bettercli"
echo "   $LIB_DIR/bettercli.jar"

PATH_HINT="$BIN_DIR"
case ":$PATH:" in
  *":$PATH_HINT:"*)
    echo "✅ PATH already contains $PATH_HINT"
    ;;
  *)
    echo ""
    echo "⚠️  Add BetterCLI to your PATH (pick one shell):"
    echo ""
    echo "  # bash"
    echo "  echo 'export PATH=\"\$HOME/.bettercli/bin:\$PATH\"' >> ~/.bashrc && source ~/.bashrc"
    echo ""
    echo "  # zsh"
    echo "  echo 'export PATH=\"\$HOME/.bettercli/bin:\$PATH\"' >> ~/.zshrc && source ~/.zshrc"
    echo ""
    echo "  # fish"
    echo "  fish -c \"fish_add_path \$HOME/.bettercli/bin\""
    ;;
esac

echo ""
echo "Then run:  bettercli"
