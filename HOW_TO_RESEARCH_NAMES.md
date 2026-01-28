# How to Research Names - Complete Guide

This guide shows you 4 different ways to research names for the flicknames-service database.

## Prerequisites

- flicknames-service running: `mvn spring-boot:run`
- Access to Claude (via claude.ai, API, or Claude Code)

---

## Method 1: Manual Research (Simplest)

**Best for:** Learning the process, researching 1-5 names

### Steps:

1. **Get names that need research:**
   ```bash
   curl http://localhost:8080/api/v1/admin/research/needed?limit=10
   ```

2. **Go to claude.ai and paste this prompt:**
   ```
   Research the name "Emma" using these guidelines:
   [paste contents of research-agent-prompt.md]
   ```

3. **Copy the JSON output**

4. **Save to a file:** `emma.json`

5. **Import:**
   ```bash
   curl -X POST http://localhost:8080/api/v1/admin/research/import \
     -H "Content-Type: application/json" \
     -d @emma.json
   ```

6. **Approve:**
   ```bash
   curl -X POST http://localhost:8080/api/v1/admin/research/1/approve
   ```

---

## Method 2: Claude Code Agent (Recommended)

**Best for:** Interactive research with validation, 5-20 names

### Steps:

1. **Open Claude Code in this directory**

2. **Ask Claude Code:**
   ```
   Research the name "Liam" following research-agent-prompt.md
   and import it to the database
   ```

3. **Claude Code will:**
   - Read the research guidelines
   - Research the name
   - Generate JSON
   - Call the import API
   - Ask if you want to approve

4. **For multiple names:**
   ```
   Research these names using research-agent-prompt.md and import them:
   - Noah
   - Olivia
   - Emma
   ```

---

## Method 3: Python Script (Automated)

**Best for:** Batch processing 20+ names, fully automated pipeline

### Setup:

```bash
# Install dependencies
pip install anthropic requests

# Set API key
export ANTHROPIC_API_KEY=your_key_here

# Make script executable
chmod +x scripts/research-names.py
```

### Usage:

**Research a single name:**
```bash
python scripts/research-names.py --name Jason --approve
```

**Research from a file:**
```bash
# Create names.txt with one name per line
echo "Emma\nLiam\nOlivia" > names.txt

python scripts/research-names.py --batch names.txt --approve
```

**Auto-research top N names:**
```bash
# Automatically fetch and research top 10 unresearched names
python scripts/research-names.py --auto 10 --approve
```

**Save without importing (for review):**
```bash
python scripts/research-names.py --auto 5 --output ./research-output --no-import
```

### Options:
- `--name NAME` - Research a single name
- `--batch FILE` - Research names from file (one per line)
- `--auto N` - Auto-fetch and research top N names from API
- `--approve` - Auto-approve after import
- `--no-import` - Don't import to DB (just generate JSON)
- `--output DIR` - Save JSON files to directory

---

## Method 4: Batch with Claude API (Advanced)

**Best for:** Processing 100+ names, custom workflows

### Setup:

```python
import anthropic
import json
import requests

client = anthropic.Anthropic(api_key="your_key")

# Read research prompt
with open("research-agent-prompt.md") as f:
    prompt_template = f.read()

def research_and_import(name):
    # Research
    message = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=2000,
        messages=[{"role": "user", "content": prompt_template.format(name=name)}]
    )

    research_json = json.loads(message.content[0].text)

    # Import
    response = requests.post(
        "http://localhost:8080/api/v1/admin/research/import",
        json=research_json
    )

    return response.json()

# Research multiple names
names = ["Emma", "Liam", "Noah", "Olivia"]
for name in names:
    result = research_and_import(name)
    print(f"Imported {name}: ID {result['id']}")
```

---

## Comparison

| Method | Speed | Automation | Control | Best For |
|--------|-------|------------|---------|----------|
| Manual | Slow | None | High | Learning, 1-5 names |
| Claude Code | Medium | Semi | High | Interactive, 5-20 names |
| Python Script | Fast | Full | Medium | Batch, 20-100 names |
| Custom API | Fastest | Full | Full | 100+ names, custom workflows |

---

## Research Workflow Tips

### 1. Start Small
Research 5-10 names manually to understand the format and quality expected.

### 2. Verify Quality
Check the first few imports:
```bash
curl http://localhost:8080/api/v1/admin/research/pending | jq
```

Review for accuracy before approving.

### 3. Batch by Category
Research similar names together for consistency:
- Biblical names: Abraham, Sarah, Moses...
- Greek mythology: Apollo, Athena, Jason...
- Modern names: Jayden, Aiden, Brayden...

### 4. Handle Errors
If import fails, check:
- Is the name already researched?
- Is JSON valid? (`jq . < file.json`)
- Is the server running?

### 5. Update vs. Re-import
If you need to fix research:
```bash
# Update existing (preserves ID and status)
curl -X PUT http://localhost:8080/api/v1/admin/research/1 \
  -H "Content-Type: application/json" \
  -d @updated.json

# Don't delete and re-import!
```

---

## Quick Start Examples

### Scenario 1: "I want to research 5 specific names"
```bash
# Use Claude Code
Research these names using research-agent-prompt.md and import them:
- Alexander
- Sophia
- Benjamin
- Isabella
- William
```

### Scenario 2: "I want to research the top 50 most popular names"
```bash
# Use Python script
python scripts/research-names.py --auto 50 --approve
```

### Scenario 3: "I have a list of 100 names to research"
```bash
# Save names to file
cat > my-names.txt << EOF
Alexander
Sophia
Benjamin
...
EOF

# Run batch
python scripts/research-names.py --batch my-names.txt --approve
```

### Scenario 4: "I want to review before importing"
```bash
# Generate JSON files only
python scripts/research-names.py --auto 10 --output ./review --no-import

# Review the files
ls review/

# Import manually after review
for file in review/*.json; do
  curl -X POST http://localhost:8080/api/v1/admin/research/import \
    -H "Content-Type: application/json" \
    -d @"$file"
done
```

---

## Research Quality Guidelines

### High Confidence (95-100)
- Multiple scholarly sources agree
- Clear etymology and history
- Well-documented usage

### Medium Confidence (80-94)
- Good sources but some variation
- Etymology generally agreed upon
- Some historical uncertainty

### Lower Confidence (60-79)
- Limited sources
- Uncertain etymology
- Conflicting information

### What to Include
✓ Original language script (e.g., Greek Ἰάσων)
✓ Root words and derivation
✓ Historical context and notable bearers
✓ Cultural variations
✓ Accurate IPA pronunciation

### What to Avoid
✗ Guessing or speculation
✗ Pop culture meanings only
✗ Unreliable sources
✗ Folk etymology without noting it

---

## Getting Help

- **Research guidelines:** See `research-agent-prompt.md`
- **API documentation:** See `TEST_RESEARCH_API.md`
- **Implementation details:** See `NAME_RESEARCH_IMPLEMENTATION.md`

## Next Steps

1. Choose your method based on volume
2. Research 5 names to test workflow
3. Review and approve
4. Scale up to larger batches
5. Build a research pipeline that works for you
