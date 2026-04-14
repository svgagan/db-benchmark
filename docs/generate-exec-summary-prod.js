const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  Footer, AlignmentType, HeadingLevel, BorderStyle, WidthType,
  ShadingType, PageNumber, LevelFormat
} = require("docx");
const fs = require("fs");

const W = 9360; // content width in DXA (8.5" - 2 x 1" margins)

// ── border helpers ───────────────────────────────────────────────────
const border  = (color = "999999") => ({ style: BorderStyle.SINGLE, size: 4, color });
const borders = (color = "BBBBBB") => ({
  top: border(color), bottom: border(color),
  left: border(color), right: border(color)
});
const noBorder  = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
const noBorders = { top: noBorder, bottom: noBorder, left: noBorder, right: noBorder };

// ── cell helpers ─────────────────────────────────────────────────────
function th(text, width) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders: borders("888888"),
    shading: { fill: "D9D9D9", type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 100, right: 100 },
    children: [new Paragraph({
      children: [new TextRun({ text, bold: true, size: 18, font: "Arial" })]
    })]
  });
}

function td(text, width, { bold = false, align = AlignmentType.LEFT, shade = "FFFFFF", size = 18 } = {}) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders: borders(),
    shading: { fill: shade, type: ShadingType.CLEAR },
    margins: { top: 70, bottom: 70, left: 100, right: 100 },
    children: [new Paragraph({
      alignment: align,
      children: [new TextRun({ text, bold, size, font: "Arial" })]
    })]
  });
}

function tdLines(lines, width, { shade = "FFFFFF", size = 17 } = {}) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders: borders(),
    shading: { fill: shade, type: ShadingType.CLEAR },
    margins: { top: 70, bottom: 70, left: 100, right: 100 },
    children: lines.map((line, i) => new Paragraph({
      spacing: { before: i === 0 ? 0 : 60, after: 0 },
      children: [new TextRun({ text: line, size, font: "Arial" })]
    }))
  });
}

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

// ── prod benchmark data ──────────────────────────────────────────────
// All latencies in ms — Amazon RDS PostgreSQL + Amazon DocumentDB
// 100,000 records · 500 iterations per query
const queries = [
  { q: "Q1",  desc: "Composite PK fetch (full record)",
    pgNI: "38.638", pgI: "39.040", moNI: "41.142",  moI: "39.231",  rows: "1",      pgSp: "1.0x", moSp: "1.0x", winner: "Tie" },
  { q: "Q2",  desc: "Latest prior_values entry (prev winner)",
    pgNI: "40.986", pgI: "41.577", moNI: "40.843",  moI: "40.525",  rows: "1",      pgSp: "1.0x", moSp: "1.0x", winner: "Tie" },
  { q: "Q3",  desc: "Address by type for known PK",
    pgNI: "37.941", pgI: "38.996", moNI: "39.333",  moI: "40.008",  rows: "1",      pgSp: "1.0x", moSp: "1.0x", winner: "Tie" },
  { q: "Q4",  desc: "demographic.ssn exact match",
    pgNI: "160.481",pgI: "38.733", moNI: "190.032", moI: "40.628",  rows: "101",    pgSp: "4.1x", moSp: "4.7x", winner: "PostgreSQL" },
  { q: "Q5",  desc: "last_name + date_of_birth compound",
    pgNI: "89.776", pgI: "39.100", moNI: "190.522", moI: "41.435",  rows: "1",      pgSp: "2.3x", moSp: "4.6x", winner: "PostgreSQL" },
  { q: "Q6",  desc: "global_pid lookup (partial key)",
    pgNI: "64.103", pgI: "38.122", moNI: "169.608", moI: "40.771",  rows: "1",      pgSp: "1.7x", moSp: "4.2x", winner: "PostgreSQL" },
  { q: "Q7",  desc: "demographic.rule_id = 'RULE_001'",
    pgNI: "161.491",pgI: "47.188", moNI: "197.433", moI: "50.539",  rows: "10,091", pgSp: "3.4x", moSp: "3.9x", winner: "PostgreSQL" },
  { q: "Q8",  desc: "mastered_date_ts last 30 days",
    pgNI: "98.320", pgI: "42.751", moNI: "168.668", moI: "44.707",  rows: "4,089",  pgSp: "2.3x", moSp: "3.8x", winner: "PostgreSQL" },
  { q: "Q9",  desc: "prior_values[*].ssn history search",
    pgNI: "249.292",pgI: "248.500",moNI: "256.348", moI: "41.925",  rows: "154",    pgSp: "1.0x", moSp: "6.1x", winner: "MongoDB" },
  { q: "Q10", desc: "mastered_date_ts range (6mo ago - 3mo ago)",
    pgNI: "69.284", pgI: "49.797", moNI: "196.420", moI: "54.565",  rows: "12,383", pgSp: "1.4x", moSp: "3.6x", winner: "PostgreSQL" },
];

// ── results table ────────────────────────────────────────────────────
const COL = [3000, 1200, 1200, 1200, 1200, 760, 800];

function resultsTable() {
  const headerRow = new TableRow({ tableHeader: true, children: [
    th("Query",           COL[0]),
    th("PG (no-idx)",     COL[1]),
    th("PG (indexed)",    COL[2]),
    th("Mongo (no-idx)",  COL[3]),
    th("Mongo (indexed)", COL[4]),
    th("Rows",            COL[5]),
    th("Winner",          COL[6]),
  ]});

  const dataRows = queries.map((r, i) => {
    const shade = i % 2 === 0 ? "FAFAFA" : "FFFFFF";
    return new TableRow({ children: [
      td(`${r.q}  ${r.desc}`, COL[0], { shade }),
      td(r.pgNI + " ms",      COL[1], { align: AlignmentType.RIGHT, shade }),
      td(r.pgI  + " ms",      COL[2], { align: AlignmentType.RIGHT, bold: r.winner === "PostgreSQL", shade }),
      td(r.moNI + " ms",      COL[3], { align: AlignmentType.RIGHT, shade }),
      td(r.moI  + " ms",      COL[4], { align: AlignmentType.RIGHT, bold: r.winner === "MongoDB", shade }),
      td(r.rows,               COL[5], { align: AlignmentType.RIGHT, shade }),
      td(r.winner,             COL[6], { bold: true, shade }),
    ]});
  });

  return new Table({ width: { size: W, type: WidthType.DXA }, columnWidths: COL, rows: [headerRow, ...dataRows] });
}

// ── speedup table ────────────────────────────────────────────────────
const COL2 = [4960, 2200, 2200];

function speedupTable() {
  const headerRow = new TableRow({ tableHeader: true, children: [
    th("Query",         COL2[0]),
    th("PG speedup",    COL2[1]),
    th("Mongo speedup", COL2[2]),
  ]});

  const dataRows = queries.map((r, i) => {
    const shade = i % 2 === 0 ? "FAFAFA" : "FFFFFF";
    return new TableRow({ children: [
      td(`${r.q}  ${r.desc}`, COL2[0], { shade }),
      td(r.pgSp,               COL2[1], { align: AlignmentType.CENTER, shade }),
      td(r.moSp,               COL2[2], { align: AlignmentType.CENTER, shade }),
    ]});
  });

  return new Table({ width: { size: W, type: WidthType.DXA }, columnWidths: COL2, rows: [headerRow, ...dataRows] });
}

// ── architecture comparison table ────────────────────────────────────
const AC = [1700, 2380, 2380, 2900];

const archRows = [
  {
    aspect: "MDM Document Model",
    docdb: "Whole-document BSON store; no concept of typed top-level columns alongside the document.",
    pg:    "Hybrid: typed columns (global_pid, global_cid, mastered_date_ts) + JSONB payload. Both parts are independently indexable.",
    verdict: "PG's hybrid model directly maps to MDM — composite key columns get native B-tree, the document body stays flexible."
  },
  {
    aspect: "Nested Array Indexing (prior_values history)",
    docdb: "Multikey index on demographic.prior_values.ssn works correctly. Q9 prod: 256 ms unindexed → 42 ms indexed (6.1x speedup).",
    pg:    "GIN (jsonb_path_ops) cannot accelerate a doubly-nested JSONPath scan. Q9 prod: stays at 248–249 ms both passes — zero index benefit.",
    verdict: "MongoDB wins this pattern convincingly on real AWS infrastructure. PostgreSQL needs prior_values normalized into a relational table."
  },
  {
    aspect: "Timestamp Range Queries (sync pipelines)",
    docdb: "mastered_date_ts stored as ISO-8601 string. Q8: 44.7 ms indexed, Q10: 54.6 ms indexed.",
    pg:    "Native TIMESTAMPTZ column with a proper B-tree. Q8: 42.8 ms indexed, Q10: 49.8 ms indexed.",
    verdict: "PostgreSQL wins, though both are usable. On AWS infrastructure the gap is smaller than local (2–5 ms vs local 3–5x)."
  },
  {
    aspect: "Identity Resolution (SSN, name + DOB)",
    docdb: "Q4 indexed: 40.6 ms, Q5 indexed: 41.4 ms. Higher speedup ratio (4.6–4.7x) due to larger unindexed scan cost.",
    pg:    "Q4 indexed: 38.7 ms, Q5 indexed: 39.1 ms. Expression indexes on JSONB paths consistently faster by ~2 ms.",
    verdict: "PostgreSQL is faster in absolute terms. Both are well within acceptable latency for MDM identity resolution."
  },
  {
    aspect: "Transaction Safety & Partial Rollback",
    docdb: "ACID at document level; no SAVEPOINT support — partial failure in a multi-step MDM merge must be unwound in application code.",
    pg:    "Full ACID with SAVEPOINT / ROLLBACK TO SAVEPOINT — partial rollback of multi-step merge operations is native.",
    verdict: "Critical for MDM merge and survivorship workflows. PostgreSQL is the safer choice."
  },
  {
    aspect: "AWS Authentication & IAM Integration",
    docdb: "Does NOT support IAM DB authentication. Requires SCRAM username/password managed via Secrets Manager.",
    pg:    "RDS IAM DB auth: an IAM policy (rds-db:connect) + a DB-level rds_iam role is enough. No static password to rotate.",
    verdict: "PostgreSQL integrates natively with AWS IAM. DocumentDB adds a secrets management layer."
  },
  {
    aspect: "Document Size Limit",
    docdb: "Hard 16 MB per-document limit inherited from MongoDB BSON wire protocol.",
    pg:    "No comparable per-row limit. JSONB can hold very large payloads without hitting a protocol ceiling.",
    verdict: "As prior_values history grows over years of mastering, large golden records could approach the DocumentDB limit."
  },
  {
    aspect: "SQL, Reporting & Analytics",
    docdb: "MongoDB aggregation pipeline; no SQL; $lookup is a limited workaround for JOINs and not optimized at scale.",
    pg:    "Full SQL — JOINs, GROUP BY, window functions, CTEs. The entire PostgreSQL ecosystem applies.",
    verdict: "Any audit report, compliance query, or analytics use case will be easier to write and maintain in PostgreSQL."
  },
  {
    aspect: "Operational Footprint on AWS",
    docdb: "Separate DocumentDB cluster to provision, monitor, patch, back up, and support. New failure modes, new runbooks.",
    pg:    "RDS PostgreSQL is likely already in use — same monitoring, same backup policy, same on-call runbook.",
    verdict: "Adding DocumentDB is a new operational surface and cost centre. PostgreSQL reuses existing investment."
  },
  {
    aspect: "Cost Model on AWS",
    docdb: "Instance + storage + I/O reads charged separately. I/O cost grows with query volume.",
    pg:    "Instance + storage. I/O included in most RDS tiers (gp3). No per-read I/O charge.",
    verdict: "At MDM query volumes DocumentDB I/O pricing can become material. PostgreSQL is cheaper to operate."
  },
];

function archTable() {
  const headerRow = new TableRow({ tableHeader: true, children: [
    th("Aspect",                 AC[0]),
    th("Amazon DocumentDB",      AC[1]),
    th("PostgreSQL (RDS)",       AC[2]),
    th("This Project's Verdict", AC[3]),
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

  return new Table({ width: { size: W, type: WidthType.DXA }, columnWidths: AC, rows: [headerRow, ...dataRows] });
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
            new TextRun({ text: "MDM Benchmark — PostgreSQL (RDS) vs DocumentDB  |  Page ", size: 16, font: "Arial", color: "888888" }),
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
        children: [new TextRun({ text: "PostgreSQL (RDS) vs Amazon DocumentDB — Production Results", size: 22, font: "Arial", color: "444444" })]
      }),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 0, after: 0 },
        children: [new TextRun({ text: "April 2026  |  Amazon RDS + DocumentDB  |  100,000 records  |  500 iterations per query", size: 18, font: "Arial", color: "777777", italic: true })]
      }),

      spacer(),

      // ── 1. What was tested ─────────────────────────────────────────
      h1("1. What Was Tested"),
      para("We ran a hands-on POC seeding 100,000 MDM golden person records into both Amazon RDS PostgreSQL and Amazon DocumentDB, then measured 10 query patterns twice — once without indexes and once with indexes. This report contains results from actual AWS infrastructure, not a local environment."),
      spacer(),
      h2("Environment"),
      bullet("PostgreSQL: Amazon RDS — database core_data_db, schema benchmark"),
      bullet("MongoDB: Amazon DocumentDB — database benchmark, collection mdm_golden_person"),
      bullet("Connection from a corporate laptop over the public internet — network round-trip latency is included in all measurements"),
      bullet("100,000 records seeded identically into both databases"),
      bullet("500 iterations per query after 5 warmup rounds"),
      spacer(),
      h2("Schema"),
      bullet("Top-level identifiers: global_pid, global_cid, mastered_date_ts"),
      bullet("Demographic block: first_name, last_name, date_of_birth, SSN, rule_id, rule_version"),
      bullet("prior_values list inside demographic — ordered history of previous demographic winners"),
      bullet("Address array — one or more addresses (PRIMARY, SECONDARY, BILLING), each with a prior_values history list"),

      spacer(),

      // ── 2. Queries ─────────────────────────────────────────────────
      h1("2. Queries"),
      para("The 10 queries reflect real MDM access patterns. Q1-Q3 are single-record fetches by known key. Q4-Q7 are field-level lookups for identity resolution and operational monitoring. Q8 and Q10 are time-window queries for sync pipelines. Q9 searches inside the prior_values history arrays."),
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
            ["Q1",  "Composite PK fetch",               "Fetch full record by id = global_pid|global_cid. Most frequent MDM operation."],
            ["Q2",  "Latest prior_values entry",         "Get last element of demographic.prior_values — the previous mastering winner."],
            ["Q3",  "Address by type for known PK",      "Fetch full record and extract just the PRIMARY (or other type) address element."],
            ["Q4",  "SSN exact match",                   "Identity resolution — does this SSN already exist as a current golden record?"],
            ["Q5",  "last_name + date_of_birth compound","Probabilistic matching when SSN is unavailable."],
            ["Q6",  "global_pid only (partial key)",     "Find all records for a person across different customer contexts."],
            ["Q7",  "rule_id lookup",                    "Operational audit — which records did RULE_001 master?"],
            ["Q8",  "mastered last 30 days",             "Recent change window for downstream sync jobs."],
            ["Q9",  "Prior SSN history search",          "Pre-merge dedup — has this SSN ever appeared in prior_values history?"],
            ["Q10", "mastered between two timestamps",   "Time-slice range query for audit windows and batch processing jobs."],
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
      para("All latencies in milliseconds (average over 500 iterations). Results include AWS network round-trip time. Bold indexed value = faster of the two databases for that query."),
      spacer(),
      resultsTable(),
      spacer(),

      h2("Index speedup (no-index avg / indexed avg)"),
      para("Higher = more benefit gained from adding an index."),
      spacer(),
      speedupTable(),

      spacer(),

      // ── 4. Key Findings ────────────────────────────────────────────
      h1("4. Key Findings"),

      h2("Network latency is the baseline floor — Q1, Q2, Q3"),
      para("On real AWS infrastructure, the round-trip network latency between the benchmark host and the databases sets a floor of approximately 38-41 ms for all single-record operations. Both databases are effectively identical on PK fetch, prior_values retrieval, and address-by-type queries. Indexes provide no additional benefit because the query itself is already optimal — the cost is entirely network time."),

      h2("Identity resolution queries — Q4 (SSN) and Q5 (name + DOB)"),
      para("Without indexes: PostgreSQL takes 160 ms (Q4) and 90 ms (Q5). MongoDB takes 190 ms for both. With indexes: PostgreSQL drops to 38.7 ms and 39.1 ms; MongoDB drops to 40.6 ms and 41.4 ms. PostgreSQL is consistently ~2 ms faster in the indexed case — a meaningful advantage at scale for high-frequency identity resolution pipelines. The lower unindexed cost for Q5 on PostgreSQL (90 ms vs 190 ms) suggests the expression index structure also benefits sequential scan planning."),

      h2("Timestamp range queries — Q8 and Q10"),
      para("PostgreSQL's native TIMESTAMPTZ column continues to outperform DocumentDB. Q8 (last 30 days, 4,089 rows): PG 42.8 ms vs Mongo 44.7 ms. Q10 (6-month to 3-month window, 12,383 rows): PG 49.8 ms vs Mongo 54.6 ms. The gap is smaller than in local testing because network latency dominates, but PostgreSQL is consistently faster for time-range scans."),

      h2("High-count result queries — Q7 (rule_id, 10,091 rows)"),
      para("PostgreSQL is faster here in prod: 47.2 ms vs MongoDB 50.5 ms. Unlike the local run where MongoDB had a small edge, on real AWS infrastructure PostgreSQL's indexed rule_id query is more efficient at streaming 10,000+ rows back to the client."),

      h2("Prior_values history search — Q9 — the key differentiator"),
      para("This is the most important finding. PostgreSQL's GIN index gives zero benefit on nested array-in-array JSONPath queries — performance stays flat at 248-249 ms both before and after indexing. MongoDB's multikey index on demographic.prior_values.ssn works correctly in prod and drops from 256 ms unindexed to 42 ms indexed — a 6.1x speedup. If pre-merge deduplication or history searches against prior_values are a regular operation, PostgreSQL would need the history normalized into a separate relational table to match this performance."),

      spacer(),

      // ── 5. Recommendation ──────────────────────────────────────────
      h1("5. Recommendation"),
      para("PostgreSQL (RDS) is the right choice for this MDM platform:"),
      spacer(),
      bullet("Core MDM operations (fetch by PK, retrieve previous winner, fetch address by type) are identical in performance on real AWS infrastructure — no advantage to switching databases for these."),
      bullet("Identity resolution queries (SSN, name+DOB) are 2 ms faster on PostgreSQL with expression indexes — measurable at MDM pipeline scale."),
      bullet("Timestamp range queries used by sync pipelines are consistently faster on PostgreSQL's native TIMESTAMPTZ column."),
      bullet("PostgreSQL is faster or tied on 9 of 10 queries in production. MongoDB wins only Q9."),
      bullet("PostgreSQL likely already exists in the team's AWS infrastructure — no new cluster, no new skillset, no additional operational overhead."),
      bullet("The one area where MongoDB leads structurally — prior_values history search (Q9, 248 ms vs 42 ms) — is addressable in PostgreSQL by normalizing audit history into a relational table. This is a known, clean migration path."),
      spacer(),
      para("If prior_values history search becomes a high-frequency operation and the team wants to keep the document structure unchanged, DocumentDB is worth reconsidering for that specific pattern only. For all other MDM operations PostgreSQL is the stronger and simpler choice.", { italic: true }),

      spacer(),

      // ── 6. Architecture Comparison ─────────────────────────────────
      h1("6. Architecture Comparison: PostgreSQL vs DocumentDB"),
      para("The table below evaluates both databases across architectural dimensions that matter for an MDM platform — operational fit, AWS integration, and long-term maintainability. Each verdict is grounded in what was observed during this POC on real AWS infrastructure."),
      spacer(),
      archTable(),
      spacer(),
      para("Summary: PostgreSQL wins or ties on 9 of 10 architecture dimensions. DocumentDB has a genuine structural advantage only on nested-array history indexing (prior_values search, Q9). Every other dimension — timestamps, identity resolution, transactions, IAM auth, SQL, cost, and operations — favours PostgreSQL.", { italic: true }),

    ]
  }]
});

Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync("../executive-summary-prod.docx", buffer);
  console.log("Written: ../executive-summary-prod.docx");
}).catch(err => {
  console.error(err);
  process.exit(1);
});
