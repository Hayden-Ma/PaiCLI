#!/usr/bin/env bash
# PaiCLI 每夜回归评测：打包 -> 跑 smoke suite -> 退出码即健康状态。
# 可选环境变量：BENCH_RUNS（每 case 采样次数，默认 1）、BENCH_OUT（产物目录）。
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -q clean package -DskipTests

java -jar target/paicli-1.0-SNAPSHOT.jar bench \
  --suite benchmarks/paicli-smoke-v0.1/suite.json \
  --runs "${BENCH_RUNS:-1}" \
  --out "${BENCH_OUT:-benchmarks/results}" \
  --allow-commands "javac *,java *,copy *,cp *,find *,findstr *,wc *,python *,cat *,mkdir *,dir *,ls *"
