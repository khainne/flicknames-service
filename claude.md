# Claude Code Notes

## Research Workflow

**ALWAYS use the research agent for name research tasks.**

- Use Task tool with `subagent_type="general-purpose"` and pass the research-agent-prompt.md instructions
- For large batches or coordination, consider using a research coordinator agent
- This ensures standardized, comprehensive research output matching the required format
- Research agent handles parallel WebFetch/WebSearch calls for efficiency
- **Raw source data** is automatically saved to `~/Documents/flicknames-research/sources/{Name}-sources.json` for future regeneration

## Debugging Import Failures

**When name research files fail to upload to the server:**

1. **Always check Railway logs first** to get the actual server-side error:
   ```bash
   railway logs --lines 200 2>&1 | grep -i "error\|warn" | tail -30
   ```

2. **Common import errors and solutions:**

   - **JSON parse errors with smart quotes:**
     - Error: `Unexpected character ('c' (code 99)): was expecting comma`
     - Cause: Smart quotes (', ', ", ") in JSON strings
     - Fix: Replace with regular quotes using Python script

   - **Invalid control characters:**
     - Error: `Invalid control character at: line X column Y`
     - Cause: Embedded newlines (`\n`) or other control chars in JSON strings
     - Fix: Remove or escape control characters

   - **Field name mismatches:**
     - Error: `Bad Request` (400) without details
     - Cause: Wrong field names in culturalUsages array
     - **CRITICAL:** DTO expects `"culture"` NOT `"culturalOrigin"`
     - Fix: Use "culture" field name as defined in NameResearchImportDTO.java line 46

3. **Validation steps before importing:**
   ```bash
   # Validate JSON syntax
   python -c "import json; json.load(open('name-research.json', encoding='utf-8')); print('Valid')"

   # Check for smart quotes
   grep -P '[''""—]' name-research.json

   # Import and check railway logs immediately
   curl -s URL/import -H "Content-Type: application/json" -d @name-research.json
   railway logs --lines 50 2>&1 | grep -i "error\|imported" | tail -10
   ```

4. **Remember:** Railway logs show the **actual server-side error**, which is often more detailed than the HTTP response.

   - **Database column size constraints:**
     - Error: `ERROR: value too long for type character varying(500)`
     - Cause: Field exceeds database column size limit
     - Fix: Alter the database schema (see Database Schema Changes below)
     - Example: `meaning` field was varchar(500), comprehensive research produces 500+ chars
     - Solution: Changed to TEXT type for unlimited length

## Database Schema Changes

**When you need to modify the database schema:**

1. **Update the entity class** (e.g., `NameResearch.java`):
   ```java
   // Before: @Column(length = 500)
   // After:
   @Column(columnDefinition = "TEXT")
   private String meaning;
   ```

2. **Commit and push to main branch** - Railway only deploys from `main`

3. **Hibernate auto-DDL may not detect all changes** - For column type changes, manual migration may be needed

4. **Connect to Railway PostgreSQL via DataGrip:**
   - Host: `switchback.proxy.rlwy.net` (check `railway variables` for current host)
   - Port: Get from DATABASE_URL (typically 41296 or similar)
   - User: `postgres`
   - Password: From DATABASE_URL
   - Database: `railway`

   Get connection string:
   ```bash
   railway variables | grep DATABASE_URL
   # Format: postgresql://postgres:PASSWORD@HOST:PORT/railway
   ```

5. **Run migration in DataGrip:**
   ```sql
   -- Verify current schema
   SELECT column_name, data_type, character_maximum_length
   FROM information_schema.columns
   WHERE table_name = 'name_research' AND column_name = 'meaning';

   -- Alter column type
   ALTER TABLE name_research ALTER COLUMN meaning TYPE TEXT;

   -- Verify change
   SELECT column_name, data_type, character_maximum_length
   FROM information_schema.columns
   WHERE table_name = 'name_research' AND column_name = 'meaning';
   ```

6. **Test the change** by attempting an import that previously failed

## File Organization

**All research files should be saved to `C:\Users\khain\Documents\flicknames-research\` NOT the project root:**

- Research JSON files: `C:\Users\khain\Documents\flicknames-research\{Name}_research.json`
- Source files: `C:\Users\khain\Documents\flicknames-research\sources\{Name}-sources.json`
- Batch files: `C:\Users\khain\Documents\flicknames-research\batch{N}.json`

**Why:**
- Keeps project root clean
- Centralizes all research artifacts
- Matches babysitter script expectations
- Easier to manage and backup research files

## Enhanced Verification Workflow

**Before approving any research, the agent must:**

1. **Save sources** to `sources/{Name}-sources.json` with WebFetch/WebSearch results
2. **Generate research JSON** with comprehensive content
3. **MANDATORY VERIFICATION** - Re-open sources file and cross-reference:
   - Etymology: root language, scripts, components correct?
   - Meaning: matches multiple sources? (can be 500+ chars now)
   - Historical facts: dates, peak years, counts accurate?
   - Contemporary data: current 2025/2026 rankings correct?
   - Source citations: minimum 4 sources listed?
   - Technical: correct DTO field names ("culture", "type")?
4. **Fix any discrepancies** immediately
5. **Only then** import and approve

This verification step prevents inaccurate information from being approved into production.
