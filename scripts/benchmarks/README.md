# VirbiusGuard benchmark harness

`virbiusguard_eval.py` reproduces the public safety-set numbers reported in the
[VirbiusGuard model card](https://huggingface.co/i1see1you/VirbiusGuard):
fixed auditor system prompt, greedy decoding, and a sample counts as a hit iff
the output contains `"hit_rule": true`.

## Requirements

```bash
pip install torch transformers
```

## Evaluation sets

| Set | Size | Source | Fields used |
|---|---:|---|---|
| WildGuardTest | 1,699 | [allenai/wildguardmix](https://huggingface.co/datasets/allenai/wildguardmix) (`test`) | `text`, `unsafe` |
| WildGuardTest adversarial slice | 458 | subset of the above (`adversarial = true`) | `text`, `unsafe` |
| XSTest | 450 | [paul-rottger/xstest](https://github.com/paul-rottger/xstest) | `text`, `unsafe` |
| ToxicChat | 5,083 | [lmsys/toxic-chat](https://huggingface.co/datasets/lmsys/toxic-chat) | `text`, `unsafe` |
| gold_1000 | 1,000 (615 unsafe / 385 safe) | internal bilingual set; not distributed | `text`, `label` |

Export a HuggingFace dataset split to JSONL (example for WildGuardTest):

```python
from datasets import load_dataset
ds = load_dataset("allenai/wildguardmix", split="test")
with open("wildguardtest.jsonl", "w", encoding="utf-8") as f:
    for row in ds:
        f.write(__import__("json").dumps({
            "text": row["prompt"],
            "unsafe": str(row["prompt_harm_label"]).lower() == "harmful",
        }) + "\n")
```

The script also accepts `label` with values `unsafe` / `safe` or `harmful` /
`unharmful`, so most existing exports work without rewriting.

## Run

```bash
# sanity check (no model download)
python3 scripts/benchmarks/virbiusguard_eval.py --self-check

# WildGuardTest, first 200 samples
python3 scripts/benchmarks/virbiusguard_eval.py \
  --data wildguardtest.jsonl --limit 200

# full gold_1000 (internal set, if you have it)
python3 scripts/benchmarks/virbiusguard_eval.py \
  --data gold_1000.jsonl --label-field label --report gold_results.jsonl

# adversarial slice
python3 scripts/benchmarks/virbiusguard_eval.py --data wildguardtest_adv.jsonl
```

Use `--chat-template` to use the tokenizer's chat template instead of the
locked `im_start` prompt. Published numbers use the locked prompt, bf16/f16
weights, and greedy decoding; CPU fp32 runs can differ by a small margin.
Parse failures count as misses, matching the harness used for the published
numbers.

## AgentDojo

AgentDojo uses its own harness and is not covered by this script. The reported
sweep (agent `deepseek-flash`, attack `important_instructions`, 949 pairs) is
in the model card; the reference harness is
[ethz-spylab/agentdojo](https://github.com/ethz-spylab/agentdojo).
