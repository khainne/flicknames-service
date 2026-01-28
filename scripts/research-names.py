#!/usr/bin/env python3
"""
Name Research Agent - Automated name research using Claude API

Usage:
    python scripts/research-names.py --name Jason
    python scripts/research-names.py --batch names.txt
    python scripts/research-names.py --auto 10

Requirements:
    pip install anthropic requests

Environment:
    ANTHROPIC_API_KEY=your_api_key
    API_BASE_URL=http://localhost:8080/api/v1 (optional)
"""

import os
import sys
import json
import argparse
import requests
from typing import Optional, List

try:
    import anthropic
except ImportError:
    print("Error: anthropic package not installed")
    print("Install with: pip install anthropic")
    sys.exit(1)

# Configuration
API_KEY = os.getenv("ANTHROPIC_API_KEY")
API_BASE_URL = os.getenv("API_BASE_URL", "http://localhost:8080/api/v1")

RESEARCH_PROMPT_TEMPLATE = """You are a name research specialist with expertise in linguistics, etymology, and cultural history.

Research the name "{name}" and provide accurate, scholarly information.

CRITICAL: Output ONLY valid JSON. No markdown, no code blocks, no explanations. Just the raw JSON object.

Required JSON structure:
{{
  "name": "{name}",
  "etymology": "Detailed linguistic origin with root words. Include original script if applicable.",
  "meaning": "Brief, clear meaning in 1-2 sentences.",
  "rootLanguage": "Primary language (Hebrew|Greek|Latin|Germanic|Celtic|Arabic|Sanskrit|Persian|Slavic|etc)",
  "history": "Historical context: when first used, how it spread, popularity trends, notable figures.",
  "pronunciation": {{
    "ipa": "/IPA notation/",
    "respelling": "REH-spelling"
  }},
  "genderClassification": "MASCULINE|FEMININE|UNISEX",
  "culturalUsages": [
    {{
      "culture": "Culture name",
      "culturalMeaning": "Culture-specific meaning if different",
      "prevalence": 1-5
    }}
  ],
  "relatedNames": [
    {{
      "name": "Related name",
      "type": "VARIANT|DIMINUTIVE|FEMININE_FORM|MASCULINE_FORM|COGNATE"
    }}
  ],
  "categories": ["Category1", "Category2"],
  "confidenceScore": 0-100
}}

Research Guidelines:
- Use reliable sources (Behind the Name, Oxford Dictionary, scholarly sources)
- Prevalence: 1=rare, 5=very common
- Confidence: 95-100 (very confident), 80-94 (confident), 60-79 (moderate)
- Be accurate about linguistic origins
- Note uncertainty when present

Output ONLY the JSON object, nothing else."""


def research_name(name: str, client: anthropic.Anthropic) -> Optional[dict]:
    """Research a single name using Claude API."""
    print(f"\n🔍 Researching: {name}")

    try:
        message = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=2000,
            messages=[{
                "role": "user",
                "content": RESEARCH_PROMPT_TEMPLATE.format(name=name)
            }]
        )

        response_text = message.content[0].text.strip()

        # Try to extract JSON if wrapped in markdown
        if "```json" in response_text:
            response_text = response_text.split("```json")[1].split("```")[0].strip()
        elif "```" in response_text:
            response_text = response_text.split("```")[1].split("```")[0].strip()

        # Parse JSON
        research_data = json.loads(response_text)
        print(f"✓ Research completed (confidence: {research_data.get('confidenceScore', 0)})")

        return research_data

    except json.JSONDecodeError as e:
        print(f"✗ Failed to parse JSON: {e}")
        print(f"Response: {response_text[:200]}...")
        return None
    except Exception as e:
        print(f"✗ Error researching name: {e}")
        return None


def import_research(research_data: dict) -> Optional[dict]:
    """Import research data to the API."""
    name = research_data.get("name", "Unknown")

    try:
        response = requests.post(
            f"{API_BASE_URL}/admin/research/import",
            json=research_data,
            headers={"Content-Type": "application/json"}
        )

        if response.status_code == 201:
            result = response.json()
            print(f"✓ Imported to database (ID: {result['id']}, Status: {result['status']})")
            return result
        else:
            print(f"✗ Import failed ({response.status_code}): {response.text}")
            return None

    except Exception as e:
        print(f"✗ Error importing: {e}")
        return None


def approve_research(research_id: int) -> bool:
    """Approve research by ID."""
    try:
        response = requests.post(f"{API_BASE_URL}/admin/research/{research_id}/approve")

        if response.status_code == 200:
            print(f"✓ Approved (ID: {research_id})")
            return True
        else:
            print(f"✗ Approval failed: {response.text}")
            return False

    except Exception as e:
        print(f"✗ Error approving: {e}")
        return False


def get_names_needed(limit: int = 10) -> List[str]:
    """Get names that need research from the API."""
    try:
        response = requests.get(f"{API_BASE_URL}/admin/research/needed?limit={limit}")

        if response.status_code == 200:
            data = response.json()
            return [item["name"] for item in data]
        else:
            print(f"✗ Failed to fetch needed names: {response.text}")
            return []

    except Exception as e:
        print(f"✗ Error fetching names: {e}")
        return []


def main():
    parser = argparse.ArgumentParser(description="Name Research Agent")
    parser.add_argument("--name", help="Research a single name")
    parser.add_argument("--batch", help="Research names from a file (one per line)")
    parser.add_argument("--auto", type=int, metavar="N", help="Auto-research top N names from API")
    parser.add_argument("--no-import", action="store_true", help="Don't import to database")
    parser.add_argument("--approve", action="store_true", help="Auto-approve after import")
    parser.add_argument("--output", help="Save JSON to file instead of importing")

    args = parser.parse_args()

    # Validate API key
    if not API_KEY:
        print("Error: ANTHROPIC_API_KEY environment variable not set")
        sys.exit(1)

    # Initialize Claude client
    client = anthropic.Anthropic(api_key=API_KEY)

    # Determine names to research
    names = []
    if args.name:
        names = [args.name]
    elif args.batch:
        with open(args.batch) as f:
            names = [line.strip() for line in f if line.strip()]
    elif args.auto:
        print(f"Fetching top {args.auto} names from API...")
        names = get_names_needed(args.auto)
        if not names:
            print("No names found")
            sys.exit(1)
    else:
        parser.print_help()
        sys.exit(1)

    print(f"\n{'='*60}")
    print(f"NAME RESEARCH AGENT")
    print(f"{'='*60}")
    print(f"Names to research: {len(names)}")
    print(f"Import to DB: {not args.no_import}")
    print(f"Auto-approve: {args.approve}")
    print(f"{'='*60}")

    # Research each name
    results = []
    for i, name in enumerate(names, 1):
        print(f"\n[{i}/{len(names)}] Processing: {name}")

        # Research
        research_data = research_name(name, client)
        if not research_data:
            print(f"⚠ Skipping {name}")
            continue

        # Save or import
        if args.output:
            output_file = f"{args.output}/{name.lower()}.json"
            with open(output_file, 'w') as f:
                json.dump(research_data, f, indent=2)
            print(f"✓ Saved to {output_file}")
        elif not args.no_import:
            imported = import_research(research_data)
            if imported and args.approve:
                approve_research(imported['id'])

        results.append(research_data)

    print(f"\n{'='*60}")
    print(f"COMPLETE: {len(results)}/{len(names)} successful")
    print(f"{'='*60}\n")


if __name__ == "__main__":
    main()
