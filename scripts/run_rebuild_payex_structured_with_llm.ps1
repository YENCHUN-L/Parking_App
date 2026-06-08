$ErrorActionPreference = 'Stop'

$outputPath = 'Output/payex_structured.json'

if (-not $env:OPENAI_API_KEY) {
  Write-Error 'OPENAI_API_KEY 尚未設定。請先設定後再執行。'
}

# 先小量測試 5 筆：
# python scripts/rebuild_payex_structured_with_llm.py --limit 5 --output $outputPath

# 全量 + 可續跑：
python scripts/rebuild_payex_structured_with_llm.py --resume --output $outputPath
