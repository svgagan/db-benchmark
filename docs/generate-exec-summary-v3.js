const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  Footer, AlignmentType, HeadingLevel, BorderStyle, WidthType,
  ShadingType, PageNumber, LevelFormat
} = require("docx");
const fs = require("fs");

const W = 9360; // content width in DXA (8.5" - 2 x 1" margins)

// ── simple border helpers ────────────────────────────────────────────
const border = (color = "999999") => ({ style: BorderStyle.SINGLE, size: 4, color });
const borders = (color = "BBBBBB") => ({
  top: border(color), bottom: border(color),
  left: border(color), right: border(color)
});
const noBorder = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
const noBorders = { top: noBorder, bottom: noBorder, left: noBorder, right: noBorder };

// ── cell helpers ─────────────────────────────────────────────────────
function th(text, width, gray = false) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders: borders("888888"),
    shading: { fill: gray ? "EFEFEF" : "D9D9D9", type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 100, right: 100 },
    children: [new Paragraph({
      children: [new TextRun({ text, bold: true, size: 18, font: "Arial" })]
    })]
  });
}

function td(text, width, { bold = false, align = AlignmentType.LEFT, shade = "FFFFFF" } = {}) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders: borders(),
    shading: { fill: shade, type: ShadingType.CLEAR },
    margins: { top: 70, bottom: 70, left: 100, right: 100 },
    children: [new Paragraph({
      alignment: align,
      children: [new TextRun({ text, bold, size: 18, font: "Arial" })]
    })]
  });
}

// ── multi-line cell (array of strings → stacked paragraphs) ─────────
function tdLines(lines, width, { bold = false, shade = "FFFFFF", size = 17 } = {}) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders: borders(),
    shading: { fill: shade, type: ShadingType.CLEAR },
    margins: { top: 70, bottom: 70, left: 100, right: 100 },
    children: lines.map((line, i) => new Paragraph({
      spacing: { before: i === 0 ? 0 : 60, after: 0 },
      children: [new TextRun({ text: line, bold, size, font: "Arial" })]
    }))
  });
}

// single-string small cell
function tds(text, width, { bold = false, align = AlignmentType.LEFT, shade = "FFFFFF" } = {}) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders: borders(),
    shading: { fill: shade, type: ShadingType.CLEAR },
    margins: { top: 70, bottom: 70, left: 100, right: 100 },
    children: [new Paragraph({
      alignment: align,
      children: [new TextRun({ text, bold, size: 17, font: "Arial" })]
    })]
  });
}

// ── paragraph helpers ────────────────────────────────────────────────
function h1(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_1,
    spacing: { before: 280, after: 100 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: "444444", space: 4 } },
    children: [new TextRun({ text, bold: true, size: 28, font: "Arial", color: "222222" })]
  });
}

function h2(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_2,
    spacing: { before: 200, after: 80 },
    children: [new TextRun({ text, bold: true, size: 22, font: "Arial", color: "333333" })]
  });
}

function para(text, { bold = false, italic = false, size = 20 } = {}) {
  return new Paragraph({
    spacing: { before: 60, after: 80 },
    children: [new TextRun({ text, bold, italic, size, font: "Arial", color: "222222" })]
  });
}

function bullet(text) {
  return new Paragraph({
    numbering: { reference: "bullets", level: 0 },
    spacing: { before: 40, after: 40 },
    children: [new TextRun({ text, size: 20, font: "Arial", color: "222222" })]
  });
}

function spacer() {
  return new Paragraph({ spacing: { before: 80, after: 0 }, children: [] });
}

// ── benchmark data ───────────────────────────────────────────────────
const queries = [
  { q: "Q1",  desc: "Composite PK fetch (full record)",          pgNI: "0.147", pgI: "0.135", moNI: "0.302",  moI: "0.150",  rows: "1",      pgSp: "1.1x",   moSp: "2.0x",   winner: "Tie" },
  { q: "Q2",  desc: "Latest prior_values entry (prev winner)",   pgNI: "0.117", pgI: "0.106", moNI: "0.195",  moI: "0.152",  rows: "1",      pgSp: "1.1x",   moSp: "1.3x",   winner: "Tie" },
  { q: "Q3",  desc: "Address by type for known PK",              pgNI: "0.135", pgI: "0.130", moNI: "0.149",  moI: "0.143",  rows: "1",      pgSp: "1.0x",   moSp: "1.0x",   winner: "Tie" },
  { q: "Q4",  desc: "demographic.ssn exact match",               pgNI: "50.818",pgI: "0.131", moNI: "21.955", moI: "0.170",  rows: "101",    pgSp: "387.9x", moSp: "129.1x", winner: "PostgreSQL" },
  { q: "Q5",  desc: "last_name + date_of_birth compound",        pgNI: "22.919",pgI: "0.115", moNI: "23.574", moI: "0.161",  rows: "1",      pgSp: "199.3x", moSp: "146.4x", winner: "PostgreSQL" },
  { q: "Q6",  desc: "global_pid lookup (partial key)",           pgNI: "12.875",pgI: "0.111", moNI: "29.936", moI: "0.161",  rows: "1",      pgSp: "116.0x", moSp: "185.9x", winner: "Tie" },
  { q: "Q7",  desc: "demographic.rule_id = 'RULE_001'",          pgNI: "53.284",pgI: "2.494", moNI: "24.649", moI: "1.866",  rows: "10,091", pgSp: "21.4x",  moSp: "13.2x",  winner: "MongoDB" },
  { q: "Q8",  desc: "mastered_date_ts last 30 days",             pgNI: "27.492",pgI: "0.265", moNI: "23.934", moI: "0.841",  rows: "4,089",  pgSp: "103.7x", moSp: "28.5x",  winner: "PostgreSQL" },
  { q: "Q9",  desc: "prior_values[*].ssn history search",        pgNI: "81.861",pgI: "84.938",moNI: "35.972", moI: "0.191",  rows: "154",    pgSp: "1.0x",   moSp: "188.3x", winner: "MongoDB" },
  { q: "Q10", desc: "mastered_date_ts range (6mo ago - 3mo ago)",pgNI: "13.715",pgI: "0.459", moNI: "28.568", moI: "2.213",  rows: "12,382", pgSp: "29.9x",  moSp: "12.9x",  winner: "PostgreSQL" },
];

// ── results table ────────────────────────────────────────────────────
const COL = [3000, 1200, 1200, 1200, 1200, 760, 800];
// Q, Desc, PG no-idx, PG idx, Mo no-idx, Mo idx, Rows, Winner

function resultsTable() {
  const headerRow = new TableRow({ tableHeader: true, children: [
    th("Query",          COL[0]),
    th("PG (no-idx)",    COL[1]),
    th("PG (indexed)",   COL[2]),
    th("Mongo (no-idx)", COL[3]),
    th("Mongo (indexed)",COL[4]),
    th("Rows",           COL[5]),
    th("Winner",         COL[6]),
  ]});

  const dataRows = queries.map((r, i) => {
    const shade = i % 2 === 0 ? "FAFAFA" : "FFFFFF";
    return new TableRow({ children: [
      td(`${r.q}  ${r.desc}`, COL[0], { bold: false, shade }),
      td(r.pgNI + " ms",      COL[1], { align: AlignmentType.RIGHT, shade }),
      td(r.pgI  + " ms",      COL[2], { align: AlignmentType.RIGHT, bold: r.winner === "PostgreSQL", shade }),
      td(r.moNI + " ms",      COL[3], { align: AlignmentType.RIGHT, shade }),
      td(r.moI  + " ms",      COL[4], { align: AlignmentType.RIGHT, bold: r.winner === "MongoDB", shade }),
      td(r.rows,               COL[5], { align: AlignmentType.RIGHT, shade }),
      td(r.winner,             COL[6], { bold: true, shade }),
    ]});
  });

  return new Table({
    width: { size: W, type: WidthType.DXA },
    columnWidths: COL,
    rows: [headerRow, ...dataRows]
  });
}

// ── speedup table ────────────────────────────────────────────────────
const COL2 = [4960, 2200, 2200];

function speedupTable() {
  const headerRow = new TableRow({ tableHeader: true, children: [
    th("Query",           COL2[0]),
    th("PG speedup",      COL2[1]),
    th("Mongo speedup",   COL2[2]),
  ]});

  const dataRows = queries.map((r, i) => {
    const shade = i % 2 === 0 ? "FAFAFA" : "FFFFFF";
    return new TableRow({ children: [
      td(`${r.q}  ${r.desc}`, COL2[0], { shade }),
      td(r.pgSp,               COL2[1], { align: AlignmentType.CENTER, shade }),
      td(r.moSp,               COL2[2], { align: AlignmentType.CENTER, shade }),
    ]});
  });

  return new Table({
    width: { size: W, type: WidthType.DXA },
    columnWidths: COL2,
    rows: [headerRow, ...dataRows]
  });
}

// ── architecture comparison table ────────────────────────────────────
// Columns: Aspect | DocumentDB | PostgreSQL | This Project's Verdict
const AC = [1700, 2380, 2380, 2900]; // sums to 9360

const archRows = [
  {
    aspect: "MDM Document Model",
    docdb: "Whole-document BSON store; no concept of typed top-level columns alongside the document.",
    pg:    "Hybrid: typed columns (global_pid, global_cid, mastered_date_ts) + JSONB payload. Both parts are independently indexable.",
    verdict: "PG's hybrid model directly maps to MDM — composite key columns get native B-tree, the document body stays flexible."
  },
  {
    aspect: "Nested Array Indexing (prior_values history)",
    docdb: "Multikey index on demographic.prior_values.ssn works correctly. Q9 dropped from 36 ms to 0.19 ms — 188x speedup.",
    pg:    "GIN (jsonb_path_ops) cannot accelerate a doubly-nested JSONPath scan (array inside array). Q9 stays at 82–85 ms regardless of indexing.",
    verdict: "MongoDB wins this pattern as-is. PostgreSQL would need prior_values normalized into a relational history table to match that speed."
  },
  {
    aspect: "Timestamp Range Queries (sync pipelines)",
    docdb: "mastered_date_ts stored as ISO-8601 string; lexicographic comparison works but is 3–5x slower. Q8: 0.84 ms, Q10: 2.21 ms.",
    pg:    "Native TIMESTAMPTZ column with a proper B-tree. Q8: 0.27 ms, Q10: 0.46 ms.",
    verdict: "PostgreSQL wins clearly. Sync and change-detection pipelines that run these queries at high frequency will see the difference."
  },
  {
    aspect: "Identity Resolution (SSN, name + DOB)",
    docdb: "Standard field indexes on string paths. Q4 indexed: 0.17 ms, Q5 indexed: 0.16 ms.",
    pg:    "Expression indexes on JSONB paths ((golden_record->>'ssn')). Q4 indexed: 0.13 ms, Q5 indexed: 0.12 ms.",
    verdict: "Both are production-viable (sub-0.2 ms). PostgreSQL expression indexes are slightly more efficient and deliver higher speedup ratios."
  },
  {
    aspect: "Transaction Safety & Partial Rollback",
    docdb: "ACID at document level; no SAVEPOINT support — an MDM merge that partially fails must be unwound in application code.",
    pg:    "Full ACID with SAVEPOINT / ROLLBACK TO SAVEPOINT — partial rollback of multi-step merge operations is native.",
    verdict: "Critical for MDM merge and survivorship workflows. PostgreSQL is the safer choice when a batch of record updates must succeed or cleanly fail."
  },
  {
    aspect: "AWS Authentication & IAM Integration",
    docdb: "Does NOT support IAM DB authentication. Requires SCRAM username/password managed via Secrets Manager rotation.",
    pg:    "RDS IAM DB auth: an IAM policy (rds-db:connect) + a DB-level rds_iam role is enough. No static password to rotate.",
    verdict: "PostgreSQL integrates natively with AWS IAM. DocumentDB adds a secrets management layer and a separate rotation Lambda."
  },
  {
    aspect: "Document Size Limit",
    docdb: "Hard 16 MB per-document limit inherited from MongoDB's BSON wire protocol.",
    pg:    "No comparable per-row limit. JSONB can hold very large payloads without hitting a protocol ceiling.",
    verdict: "As prior_values history grows over years of mastering operations, large golden records could approach the DocumentDB ceiling. PG has no such constraint."
  },
  {
    aspect: "SQL, Reporting & Analytics",
    docdb: "MongoDB aggregation pipeline syntax; no SQL; $lookup is a limited stand-in for JOINs and is not optimized for scale.",
    pg:    "Full SQL — JOINs, GROUP BY, window functions, CTEs, sub-queries. The entire PostgreSQL ecosystem (pgAdmin, reporting tools, BI connectors) applies.",
    verdict: "Any audit report, compliance query, or analytics use case against MDM data will be easier to write, maintain, and hand off in PostgreSQL."
  },
  {
    aspect: "Schema Guardrails",
    docdb: "Fully schema-less; no server-side enforcement unless JSON Schema validation is manually configured per collection.",
    pg:    "Top-level columns are strongly typed. JSONB is flexible but can be constrained with CHECK constraints or JSON Schema validation functions.",
    verdict: "Golden records should have a consistent shape. PostgreSQL gives you guardrails without sacrificing document flexibility."
  },
  {
    aspect: "Operational Footprint on AWS",
    docdb: "A separate DocumentDB cluster to provision, monitor, patch, back up, and support. New failure modes, new runbooks, new on-call knowledge.",
    pg:    "If RDS PostgreSQL is already in use, this is zero new infrastructure — same monitoring, same backup policy, same DBA skillset.",
    verdict: "Adding DocumentDB is a new operational surface and a new cost centre. PostgreSQL reuses existing investment."
  },
  {
    aspect: "Cost Model on AWS",
    docdb: "Instance + storage + I/O reads charged separately. I/O cost grows with query volume.",
    pg:    "Instance + storage. I/O is included in most RDS tiers (gp3 volumes). No per-read I/O charge.",
    verdict: "At MDM query volumes (identity lookups, sync pipelines), DocumentDB I/O pricing can become material. PostgreSQL is cheaper to operate at comparable workloads."
  },
  {
    aspect: "Future Query Flexibility",
    docdb: "Best suited for teams fully committed to a document-only access model. SQL-style analytics require external tools.",
    pg:    "SQL and JSONB coexist in one engine. Complex queries can mix typed columns with JSONB operators in a single statement.",
    verdict: "As MDM requirements evolve — compliance reporting, cross-entity joins, ad-hoc analytics — PostgreSQL has more headroom without adding another system."
  },
];

function archTable() {
  const headerRow = new TableRow({ tableHeader: true, children: [
    th("Aspect",                    AC[0]),
    th("Amazon DocumentDB",         AC[1]),
    th("PostgreSQL (RDS)",          AC[2]),
    th("This Project's Verdict",    AC[3]),
  ]});

  const dataRows = archRows.map((r, i) => {
    const shade = i % 2 === 0 ? "FAFAFA" : "FFFFFF";
    return new TableRow({ children: [
      tds(r.aspect,  AC[0], { bold: true, shade }),
      tdLines([r.docdb],   AC[1], { shade }),
      tdLines([r.pg],      AC[2], { shade }),
      tdLines([r.verdict], AC[3], { shade }),
    ]});
  });

  return new Table({
    width: { size: W, type: WidthType.DXA },
    columnWidths: AC,
    rows: [headerRow, ...dataRows]
  });
}

// ── document ─────────────────────────────────────────────────────────
const doc = new Document({
  numbering: {
    config: [{
      reference: "bullets",
      levels: [{ level: 0, format: LevelFormat.BULLET, text: "-",
        alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 480, hanging: 240 } } } }]
    }]
  },
  styles: {
    default: { document: { run: { font: "Arial", size: 20 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 28, bold: true, font: "Arial" },
        paragraph: { spacing: { before: 280, after: 100 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 22, bold: true, font: "Arial" },
        paragraph: { spacing: { before: 200, after: 80 }, outlineLevel: 1 } },
    ]
  },
  sections: [{
    properties: {
      page: {
        size: { width: 12240, height: 15840 },
        margin: { top: 1080, right: 1080, bottom: 1080, left: 1080 }
      }
    },
    footers: {
      default: new Footer({ children: [
        new Paragraph({
          alignment: AlignmentType.CENTER,
          border: { top: { style: BorderStyle.SINGLE, size: 4, color: "CCCCCC", space: 4 } },
          children: [
            new TextRun({ text: "MDM Benchmark — PostgreSQL vs DocumentDB  |  Page ", size: 16, font: "Arial", color: "888888" }),
            new TextRun({ children: [PageNumber.CURRENT], size: 16, font: "Arial", color: "888888" }),
          ]
        })
      ]})
    },
    children: [

      // ── Title ──────────────────────────────────────────────────────
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 0, after: 60 },
        children: [new TextRun({ text: "MDM Golden Record — Database Benchmark", bold: true, size: 40, font: "Arial" })]
      }),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 0, after: 40 },
        children: [new TextRun({ text: "PostgreSQL (JSONB) vs DocumentDB (MongoDB-compatible)", size: 22, font: "Arial", color: "444444" })]
      }),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 0, after: 0 },
        children: [new TextRun({ text: "April 2026  |  100,000 records  |  500 iterations per query", size: 18, font: "Arial", color: "777777", italic: true })]
      }),

      spacer(),

      // ── 1. What was tested ─────────────────────────────────────────
      h1("1. What Was Tested"),
      para("We ran a hands-on POC seeding 100,000 MDM golden person records into both databases and measuring 10 query patterns twice — once without indexes and once with indexes."),
      spacer(),
      h2("Schema"),
      para("Each golden person record contains:"),
      bullet("Top-level identifiers: global_pid, global_cid, mastered_date_ts"),
      bullet("Demographic block: first_name, last_name, date_of_birth, SSN, rule_id, rule_version"),
      bullet("prior_values list inside demographic — ordered history of previous demographic winners, each with a lost_against_rule field"),
      bullet("Address array — one or more addresses (PRIMARY, SECONDARY, BILLING), each also with a prior_values history list"),
      spacer(),
      h2("Data distribution"),
      bullet("100,000 records seeded identically into both databases"),
      bullet("SSN drawn from a shared pool of 1,000 values — roughly 100 records per SSN"),
      bullet("10 mastering rules (RULE_001 to RULE_010), uniform — about 10,000 records each"),
      bullet("mastered_date_ts spread uniformly over the past 2 years"),
      bullet("0-3 prior_values entries per demographic; 0-2 per address"),

      spacer(),

      // ── 2. Queries ─────────────────────────────────────────────────
      h1("2. Queries"),
      para("The 10 queries reflect real MDM access patterns. Q1-Q3 are single-record fetches by known key. Q4-Q7 are field-level lookups used for identity resolution and operational monitoring. Q8 and Q10 are time-window queries used by sync pipelines. Q9 searches inside the prior_values history arrays."),
      spacer(),

      new Table({
        width: { size: W, type: WidthType.DXA },
        columnWidths: [800, 2800, 5760],
        rows: [
          new TableRow({ tableHeader: true, children: [
            th("#",           800),
            th("Description", 2800),
            th("Purpose",     5760),
          ]}),
          ...[
            ["Q1",  "Composite PK fetch",              "Fetch full record by id = global_pid|global_cid. Most frequent MDM operation."],
            ["Q2",  "Latest prior_values entry",        "Get last element of demographic.prior_values — the previous mastering winner."],
            ["Q3",  "Address by type for known PK",     "Fetch full record and extract just the PRIMARY (or other type) address element."],
            ["Q4",  "SSN exact match",                  "Identity resolution — does this SSN already exist as a current golden record?"],
            ["Q5",  "last_name + date_of_birth compound","Probabilistic matching when SSN is unavailable."],
            ["Q6",  "global_pid only (partial key)",    "Find all records for a person across different customer contexts."],
            ["Q7",  "rule_id lookup",                   "Operational audit — which records did RULE_001 master?"],
            ["Q8",  "mastered last 30 days",            "Recent change window for downstream sync jobs."],
            ["Q9",  "Prior SSN history search",         "Pre-merge dedup — has this SSN ever appeared in prior_values history?"],
            ["Q10", "mastered between two timestamps",  "Time-slice range query for audit windows and batch processing jobs."],
          ].map(([num, desc, purpose], i) => new TableRow({ children: [
            td(num,    800,  { shade: i % 2 === 0 ? "FAFAFA" : "FFFFFF", bold: true }),
            td(desc,   2800, { shade: i % 2 === 0 ? "FAFAFA" : "FFFFFF" }),
            td(purpose,5760, { shade: i % 2 === 0 ? "FAFAFA" : "FFFFFF" }),
          ]}))
        ]
      }),

      spacer(),

      // ── 3. Results ─────────────────────────────────────────────────
      h1("3. Benchmark Results"),
      para("All latencies in milliseconds (average over 500 iterations). Bold indexed value = faster of the two databases for that query."),
      spacer(),
      resultsTable(),
      spacer(),

      h2("Index speedup (no-index avg / indexed avg)"),
      para("Higher = more benefit gained from adding an index."),
      spacer(),
      speedupTable(),

      spacer(),

      // ── 4. Findings ────────────────────────────────────────────────
      h1("4. Key Findings"),

      h2("PK and single-record fetches — Q1, Q2, Q3"),
      para("Both databases are sub-millisecond (0.10 – 0.15 ms) for all three single-record operations. Fetching a full record by composite PK, retrieving the previous winner from prior_values, and extracting a specific address type — all effectively equivalent. For the core MDM fetch/save pattern the choice of database makes no practical difference."),

      h2("Identity resolution — Q4 (SSN) and Q5 (name + DOB)"),
      para("Without indexes both queries take 22-51 ms. With indexes both drop to around 0.13 ms (PostgreSQL) and 0.16-0.17 ms (MongoDB). PostgreSQL's expression index and compound expression index are slightly faster in absolute terms. The speedup ratio is higher for PostgreSQL (388x on SSN vs 129x) because its unindexed scan was slower to begin with — the indexed result is what matters in production and both are well under 1 ms."),

      h2("Timestamp range queries — Q8 and Q10"),
      para("PostgreSQL's native TIMESTAMPTZ column gives it a clear advantage here. Q8 (last 30 days): PG 0.27 ms vs MongoDB 0.84 ms. Q10 (6-month to 3-month window, 12,382 rows): PG 0.46 ms vs MongoDB 2.21 ms. MongoDB stores mastered_date_ts as an ISO-8601 string and compares it lexicographically — it works correctly but is 3-5x slower than a true timestamp B-tree. For sync pipelines that run these queries frequently, this matters."),

      h2("High-count result queries — Q7 (rule_id, 10k rows)"),
      para("MongoDB is slightly faster in the indexed case: 1.87 ms vs 2.49 ms. When the result set is large, MongoDB's document retrieval path has a small edge. Not a significant difference in practice."),

      h2("Prior_values history search — Q9"),
      para("This is the starkest finding. PostgreSQL's GIN index gives zero benefit on nested array-in-array JSONPath queries — performance stays at 82-85 ms both before and after indexing. MongoDB's multikey index on demographic.prior_values.ssn works correctly and drops from 36 ms to 0.19 ms (188x speedup). If searching inside prior_values history is a regular operation, PostgreSQL would need the history normalized into a separate relational table to achieve comparable performance. As a JSONB structure it cannot be indexed at that depth."),

      spacer(),

      // ── 5. Recommendation ──────────────────────────────────────────
      h1("5. Recommendation"),
      para("PostgreSQL is the right choice for this MDM platform for the following reasons:"),
      spacer(),
      bullet("The core operations — fetch by PK, fetch previous winner, fetch address by type — are identical in performance. No advantage to switching."),
      bullet("Identity resolution queries (SSN, name+DOB) are faster on PostgreSQL with expression indexes, which is critical for high-throughput matching pipelines."),
      bullet("Timestamp range queries used by sync pipelines are 3-5x faster on PostgreSQL's native TIMESTAMPTZ column than MongoDB's string comparison."),
      bullet("PostgreSQL already exists in the team's infrastructure. No new system to operate, no new skillset, no additional cost."),
      bullet("The one area where MongoDB leads structurally — prior_values history search (Q9) — can be addressed in PostgreSQL by normalizing audit history into a relational table. This is a known, clean migration path."),
      spacer(),
      para("If searching inside prior_values history becomes a high-frequency operation and the team prefers to keep the document structure as-is, DocumentDB is worth reconsidering for that specific access pattern. For everything else PostgreSQL is the stronger choice.", { italic: true }),

      spacer(),

      // ── 6. Architecture Comparison ─────────────────────────────────
      h1("6. Architecture Comparison: PostgreSQL vs DocumentDB"),
      para("The table below evaluates both databases across architectural dimensions that matter for an MDM platform — not just raw query speed but operational fit, AWS integration, and long-term maintainability. Each verdict is grounded in what we observed during this POC or in documented AWS behaviour."),
      spacer(),
      archTable(),
      spacer(),
      para("Summary: PostgreSQL wins or ties on 10 of 12 dimensions. DocumentDB has a genuine structural advantage only on nested-array history indexing (prior_values search). Everything else — timestamps, identity resolution, transactions, IAM auth, SQL, cost, and operations — favours PostgreSQL, and most of those advantages compound over time as the MDM platform grows.", { italic: true }),

    ]
  }]
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync("../executive-summary-v3.docx", buffer);
  console.log("Written: ../executive-summary-v3.docx");
}).catch(err => {
  console.error(err);
  process.exit(1);
});
