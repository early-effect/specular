#!/bin/bash
set -euo pipefail
mkdir -p target/site/atlas
echo ghost > target/site/ghost.txt
echo stale > target/site/atlas/stale.txt
