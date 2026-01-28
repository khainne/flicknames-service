# Testing the Name Research API

This document provides step-by-step curl commands to test the name research feature.

## Prerequisites

1. Start the application:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=production
   ```

2. Ensure PostgreSQL is running and accessible

## Test Workflow

### Step 1: Check Research Statistics

Get current research coverage stats:

```bash
curl -X GET http://localhost:8080/api/v1/admin/research/stats
```

Expected response:
```json
{
  "totalResearch": 0,
  "approvedResearch": 0,
  "pendingResearch": 0,
  "rejectedResearch": 0,
  "totalNames": 98000,
  "coveragePercentage": 0.0
}
```

### Step 2: Get Names Needing Research

Get top 10 popular names without research:

```bash
curl -X GET "http://localhost:8080/api/v1/admin/research/needed?limit=10"
```

Expected response:
```json
[
  {
    "name": "James",
    "sex": "M",
    "totalCount": 5234567
  },
  {
    "name": "Mary",
    "sex": "F",
    "totalCount": 4123456
  },
  ...
]
```

### Step 3: Import Research Data

Import research for "Jason":

```bash
curl -X POST http://localhost:8080/api/v1/admin/research/import \
  -H "Content-Type: application/json" \
  -d @sample-research-data.json
```

Expected response:
```json
{
  "id": 1,
  "name": "Jason",
  "status": "PENDING",
  "etymology": "From Greek Ἰάσων (Iason) meaning 'healer'...",
  "meaning": "A name meaning 'healer' or 'to heal'...",
  ...
}
```

### Step 4: Import Additional Names

Import research for "Emma":

```bash
curl -X POST http://localhost:8080/api/v1/admin/research/import \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Emma",
    "etymology": "From Germanic ermen meaning \"whole\" or \"universal\"",
    "meaning": "A name meaning \"universal\" or \"whole\", often associated with strength",
    "rootLanguage": "Germanic",
    "history": "Originally a short form of Germanic names beginning with the element ermen. Introduced to England by Emma of Normandy, wife of King Ethelred II and later King Canute. The name gained popularity in the 19th century.",
    "pronunciation": {
      "ipa": "/ˈɛmə/",
      "respelling": "EM-uh"
    },
    "genderClassification": "FEMININE",
    "culturalUsages": [
      {
        "culture": "English",
        "prevalence": 5
      },
      {
        "culture": "German",
        "prevalence": 5
      },
      {
        "culture": "French",
        "prevalence": 4
      }
    ],
    "relatedNames": [
      {
        "name": "Emily",
        "type": "COGNATE"
      },
      {
        "name": "Em",
        "type": "DIMINUTIVE"
      },
      {
        "name": "Emmy",
        "type": "DIMINUTIVE"
      }
    ],
    "categories": ["Germanic", "Royal"],
    "confidenceScore": 90
  }'
```

### Step 5: List Pending Research

Get all pending research entries:

```bash
curl -X GET "http://localhost:8080/api/v1/admin/research/pending?page=0&size=20"
```

Expected response:
```json
{
  "content": [
    {
      "id": 1,
      "name": "Jason",
      "status": "PENDING",
      ...
    },
    {
      "id": 2,
      "name": "Emma",
      "status": "PENDING",
      ...
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "number": 0,
  "size": 20
}
```

### Step 6: View Specific Research Entry

Get details for research ID 1:

```bash
curl -X GET http://localhost:8080/api/v1/admin/research/1
```

### Step 7: Update Research (Optional)

If you need to fix or update the research content, use the update endpoint.
This preserves the status (approved research stays approved):

```bash
curl -X PUT http://localhost:8080/api/v1/admin/research/1 \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Jason",
    "etymology": "From Greek Ἰάσων (Iason) meaning \"healer\", derived from Greek ἰάομαι (iaomai) \"to heal\".",
    "meaning": "A name meaning \"healer\" or \"to heal\", from ancient Greek mythology. Updated for clarity.",
    "rootLanguage": "Greek",
    "history": "In Greek mythology, Jason was the leader of the Argonauts who went on a quest for the Golden Fleece. The name has been used in the English-speaking world since the Protestant Reformation, though it was not particularly common until the 20th century. It saw a significant surge in popularity during the 1970s and 1980s.",
    "pronunciation": {
      "ipa": "/ˈdʒeɪsən/",
      "respelling": "JAY-sun"
    },
    "genderClassification": "MASCULINE",
    "culturalUsages": [
      {
        "culture": "English",
        "culturalMeaning": "Modern English usage",
        "prevalence": 5
      },
      {
        "culture": "Greek Mythology",
        "culturalMeaning": "Hero who led the Argonauts",
        "prevalence": 5
      },
      {
        "culture": "Biblical",
        "culturalMeaning": "Jason of Thessalonica in Acts 17:5-9",
        "prevalence": 3
      }
    ],
    "relatedNames": [
      {
        "name": "Jayson",
        "type": "VARIANT"
      },
      {
        "name": "Jace",
        "type": "DIMINUTIVE"
      },
      {
        "name": "Jay",
        "type": "DIMINUTIVE"
      },
      {
        "name": "Jase",
        "type": "DIMINUTIVE"
      }
    ],
    "categories": [
      "Greek Mythology",
      "Biblical",
      "Ancient Greek"
    ],
    "confidenceScore": 95
  }'
```

Expected response:
```json
{
  "id": 1,
  "name": "Jason",
  "status": "PENDING",  // Status is preserved!
  "meaning": "A name meaning \"healer\" or \"to heal\", from ancient Greek mythology. Updated for clarity.",
  ...
}
```

### Step 8: Approve Research

Approve "Jason" research:

```bash
curl -X POST http://localhost:8080/api/v1/admin/research/1/approve
```

Expected response:
```json
{
  "id": 1,
  "name": "Jason",
  "status": "APPROVED",
  ...
}
```

### Step 9: Bulk Approve

Approve multiple entries at once:

```bash
curl -X POST http://localhost:8080/api/v1/admin/research/bulk-approve \
  -H "Content-Type: application/json" \
  -d '{
    "ids": [2]
  }'
```

### Step 10: Reject Research (Example)

If you want to reject an entry:

```bash
curl -X POST http://localhost:8080/api/v1/admin/research/3/reject \
  -H "Content-Type: application/json" \
  -d '{
    "notes": "Etymology needs more citations. Please provide sources for the Greek origin claim."
  }'
```

### Step 11: Query Public Research Endpoint

Now that research is approved, test the public endpoint:

```bash
curl -X GET http://localhost:8080/api/v1/names/Jason/research
```

Expected response (only approved research visible):
```json
{
  "id": 1,
  "name": "Jason",
  "etymology": "From Greek Ἰάσων (Iason) meaning 'healer'...",
  "meaning": "A name meaning 'healer' or 'to heal'...",
  "rootLanguage": "Greek",
  "pronunciation": {
    "ipa": "/ˈdʒeɪsən/",
    "respelling": "JAY-sun"
  },
  "genderClassification": "MASCULINE",
  "confidenceScore": 95,
  "culturalUsages": [
    {
      "culturalOrigin": "English",
      "prevalence": 5
    },
    ...
  ],
  "relatedNames": [
    {
      "name": "Jayson",
      "relationshipType": "VARIANT"
    },
    ...
  ],
  "categories": ["Greek Mythology", "Biblical", "Ancient Greek"],
  "updatedAt": "2025-01-28T..."
}
```

### Step 12: Test Full Name Details Endpoint

Get comprehensive name information:

```bash
curl -X GET http://localhost:8080/api/v1/names/Jason/full
```

Expected response:
```json
{
  "name": "Jason",
  "research": {
    "etymology": "From Greek Ἰάσων (Iason) meaning 'healer'...",
    "meaning": "A name meaning 'healer' or 'to heal'...",
    "pronunciation": {
      "ipa": "/ˈdʒeɪsən/",
      "respelling": "JAY-sun"
    },
    ...
  },
  "ssaStats": {
    "sex": "M",
    "totalCount": 634523,
    "peakYear": 1974,
    "peakCount": 45212,
    "firstYear": 1880,
    "lastYear": 2023,
    "recentYears": [
      {
        "year": 2023,
        "count": 3456,
        "rank": 125
      },
      ...
    ]
  },
  "namesakes": [
    {
      "id": 123,
      "firstName": "Jason",
      "lastName": "Momoa",
      "fullName": "Jason Momoa",
      "profilePath": "/xyz123.jpg",
      "gender": "Male"
    },
    {
      "id": 124,
      "firstName": "Jason",
      "lastName": "Statham",
      "fullName": "Jason Statham",
      "profilePath": "/abc456.jpg",
      "gender": "Male"
    },
    ...
  ]
}
```

### Step 13: Test Non-Existent Name

Try to get research for a name that doesn't exist:

```bash
curl -X GET http://localhost:8080/api/v1/names/XYZ123/research
```

Expected response: `404 Not Found`

### Step 14: Verify Statistics After Import

Check updated statistics:

```bash
curl -X GET http://localhost:8080/api/v1/admin/research/stats
```

Expected response:
```json
{
  "totalResearch": 2,
  "approvedResearch": 2,
  "pendingResearch": 0,
  "rejectedResearch": 0,
  "totalNames": 98000,
  "coveragePercentage": 0.002
}
```

## Error Cases to Test

### Duplicate Import

Try to import the same name twice:

```bash
curl -X POST http://localhost:8080/api/v1/admin/research/import \
  -H "Content-Type: application/json" \
  -d @sample-research-data.json
```

Expected: `400 Bad Request` with error message about duplicate

### Invalid ID

Try to approve non-existent research:

```bash
curl -X POST http://localhost:8080/api/v1/admin/research/99999/approve
```

Expected: `404 Not Found`

### Missing Required Fields

Try to import with missing fields:

```bash
curl -X POST http://localhost:8080/api/v1/admin/research/import \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test",
    "etymology": "Test etymology"
  }'
```

Expected: `400 Bad Request` with validation errors

## Swagger UI Testing

Once the application is running, you can also test all endpoints interactively at:

```
http://localhost:8080/swagger-ui.html
```

Look for:
- **Names** tag - Contains the `/names/{name}/full` endpoint
- **Name Research** tag (or similar) - Contains all research endpoints

## Database Verification

After running tests, you can verify the data in PostgreSQL:

```sql
-- Check research entries
SELECT id, name, status, gender_classification, confidence_score
FROM name_research;

-- Check cultural usages
SELECT nr.name, ncu.cultural_origin, ncu.prevalence
FROM name_research nr
JOIN name_cultural_usage ncu ON nr.id = ncu.name_research_id;

-- Check related names
SELECT nr.name, nrel.related_name, nrel.relationship_type
FROM name_research nr
JOIN name_relationship nrel ON nr.id = nrel.name_research_id;

-- Check categories
SELECT nr.name, nc.category
FROM name_research nr
JOIN name_category nc ON nr.id = nc.name_research_id;

-- Get research with all relationships
SELECT
  nr.name,
  nr.status,
  COUNT(DISTINCT ncu.id) as cultural_usages_count,
  COUNT(DISTINCT nrel.id) as related_names_count,
  COUNT(DISTINCT nc.id) as categories_count
FROM name_research nr
LEFT JOIN name_cultural_usage ncu ON nr.id = ncu.name_research_id
LEFT JOIN name_relationship nrel ON nr.id = nrel.name_research_id
LEFT JOIN name_category nc ON nr.id = nc.name_research_id
GROUP BY nr.id, nr.name, nr.status;
```

## Next Steps

1. Import research for top 100 popular names
2. Set up a research workflow in Claude Code
3. Create a batch import script for multiple names
4. Add authentication/authorization to admin endpoints
5. Create a simple admin UI for reviewing and approving research
