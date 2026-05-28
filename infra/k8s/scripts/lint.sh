#!/usr/bin/env bash
# Lint and template-render every chart under infra/k8s/charts.
# Usage:  bash infra/k8s/scripts/lint.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHARTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/charts"

if ! command -v helm >/dev/null 2>&1; then
  echo "ERROR: helm not found in PATH. Install Helm 3.13+: https://helm.sh/docs/intro/install/" >&2
  exit 1
fi

# Sub-charts first, umbrella last (umbrella references file://../<sub> repos).
SUBCHARTS=(api-gateway auth-service accounts-service transactions-service notifications-service web-portal)

echo "==> Linting sub-charts"
for c in "${SUBCHARTS[@]}"; do
  echo "---- helm lint $c"
  helm lint "$CHARTS_DIR/$c"
done

echo "==> Building umbrella dependencies"
( cd "$CHARTS_DIR/microservice-bank" && helm dependency update --skip-refresh )

echo "==> Linting umbrella chart"
helm lint "$CHARTS_DIR/microservice-bank" \
  -f "$CHARTS_DIR/microservice-bank/values.yaml"

echo "==> helm template (default values)"
helm template msbank "$CHARTS_DIR/microservice-bank" \
  --namespace msbank > /dev/null

echo "==> helm template (AKS values)"
helm template msbank "$CHARTS_DIR/microservice-bank" \
  --namespace msbank \
  -f "$CHARTS_DIR/microservice-bank/values-aks.yaml" > /dev/null

echo "==> helm template (local values)"
helm template msbank "$CHARTS_DIR/microservice-bank" \
  --namespace msbank \
  -f "$CHARTS_DIR/microservice-bank/values-local.yaml" > /dev/null

echo "OK — all charts lint clean and render."
