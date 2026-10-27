"""
Proves every shipped Room migration is additive and preserves existing rows.

Run with:  python tools/verify_migration.py

Uses Python's bundled sqlite3 -- the same engine Android ships -- so migrations can be verified
WITHOUT a connected device. That matters for this project specifically: the app is a documentation
tool holding field observations that cannot be re-collected, so "this upgrade does not destroy data"
needs to be checkable on every change, including in CI, not only when someone has a phone plugged in.

What it does, for each consecutive pair of exported schema versions:
  1. Recovers the migration's SQL from Migrations.kt itself, so it tests what ships, not a copy.
  2. Classifies every statement and accepts ONLY the additive forms the project's policy allows:
     CREATE TABLE / CREATE INDEX for a brand-new table, and ALTER TABLE ... ADD COLUMN on an existing
     one (nullable, or NOT NULL with a DEFAULT). Anything else -- DROP, RENAME, DELETE, UPDATE,
     INSERT, a table rebuild -- fails.
  3. Diffs the two exported schemas: an untouched table must be byte-identical; an altered table must
     keep every old column byte-identical and gain exactly the columns the migration adds.
  4. Builds a real database at the OLD version, fills every table with representative rows,
     runs the migration, and checks every row is still there and unchanged on its old columns, and
     that each added column reads its declared default on those old rows.
  5. Compares the migrated database's PRAGMA table_info / foreign_key_list / index_list against the
     NEW exported schema -- the same comparison Room performs when opening the database on a phone.
Then it runs the WHOLE chain from the oldest exported schema to the newest in one database, which is
the path a device that skipped several releases takes.

The on-device equivalent is MigrationTest (androidTest), which additionally runs Room's own schema
validator. The two overlap but are not identical: this harness also greps the migration source and
diffs the schema JSON version-to-version.
"""
import io, json, os, re, sqlite3, sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_DIR = os.path.join(REPO, "app", "schemas", "com.gops.spatialmapper.data.AppDatabase")
MIGRATIONS_KT = os.path.join(REPO, "app", "src", "main", "java", "com", "gops", "spatialmapper",
                             "data", "Migrations.kt")

# Words that may never appear in a migration, outside a foreign key's ON DELETE / ON UPDATE action
# (which is part of a new table's definition, not a statement that deletes or updates anything).
DESTRUCTIVE = ("DROP", "RENAME", "DELETE", "UPDATE", "INSERT", "REPLACE")

ADD_COLUMN = re.compile(r"^ALTER TABLE `(\w+)` ADD COLUMN `(\w+)` (.+)$", re.I)
CREATE_TABLE = re.compile(r"^CREATE TABLE IF NOT EXISTS `(\w+)`", re.I)
CREATE_INDEX = re.compile(r"^CREATE (?:UNIQUE )?INDEX IF NOT EXISTS `(\w+)` ON `(\w+)`", re.I)
FK_ACTION = re.compile(r"\bON (?:DELETE|UPDATE) (?:NO ACTION|CASCADE|SET NULL|SET DEFAULT|RESTRICT)\b",
                       re.I)

failures = []


def check(label, ok, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + label + (("\n        " + detail) if detail and not ok else ""))
    if not ok:
        failures.append(label)


def load(version):
    with io.open(os.path.join(SCHEMA_DIR, "%d.json" % version), encoding="utf-8") as f:
        return json.load(f)["database"]


def create_sql(entity):
    return entity["createSql"].replace("${TABLE_NAME}", entity["tableName"])


def index_sql(index, table):
    return index["createSql"].replace("${TABLE_NAME}", table)


def migration_statements(source, from_version, to_version):
    """Every string passed to execSQL inside MIGRATION_<from>_<to>, in order."""
    marker = "val MIGRATION_%d_%d" % (from_version, to_version)
    if marker not in source:
        return None
    body = source[source.index(marker):]
    end = body.index("\n}\n") if "\n}\n" in body else len(body)
    body = body[:end]
    statements = []
    for call in re.split(r"\bexecSQL\b", body)[1:]:
        pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', call)
        if pieces:
            statements.append("".join(pieces))
    return statements


def classify(statement):
    """('add_column', table, column, definition) | ('create_table', table) |
    ('create_index', index, table) | ('other', statement)."""
    text = statement.strip()
    m = ADD_COLUMN.match(text)
    if m:
        return ("add_column", m.group(1), m.group(2), m.group(3))
    m = CREATE_TABLE.match(text)
    if m:
        return ("create_table", m.group(1))
    m = CREATE_INDEX.match(text)
    if m:
        return ("create_index", m.group(1), m.group(2))
    return ("other", statement)


def declared_default(definition):
    """The DEFAULT of an ADD COLUMN definition as SQLite will read it back, or None."""
    m = re.search(r"\bDEFAULT\s+('(?:[^']|'')*'|-?[0-9.]+|NULL)", definition, re.I)
    if not m or m.group(1).upper() == "NULL":
        return None
    literal = m.group(1)
    if literal.startswith("'"):
        return literal[1:-1].replace("''", "'")
    return float(literal) if "." in literal else int(literal)


def fields_by_name(entity):
    return {f["columnName"]: f for f in entity["fields"]}


def sample_value(field, table):
    """A representative value for one column, exercising nullables as NULL."""
    name, affinity, not_null = field["columnName"], field["affinity"], field.get("notNull", False)
    if name == "id":
        return None                                   # let AUTOINCREMENT assign
    if not not_null:
        return None                                   # exercise the nullable columns
    if name == "polygonVertices":
        return "12.9716,77.5946;12.9716,77.5948;12.9718,77.5948"
    if name == "points":
        return "12.9716,77.5946,1700000000000;12.9718,77.5948,1700000003000"
    if name == "label":
        return 'Neem, "by the gate"'
    if affinity == "TEXT":
        return "%s-text" % table
    if affinity == "REAL":
        return 1.5
    return 1700000000000


def create_database(schema):
    """An in-memory database at [schema]'s version, built from its exported DDL."""
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for entity in schema["entities"]:
        db.execute(create_sql(entity))
        for index in entity.get("indices", []):
            db.execute(index_sql(index, entity["tableName"]))
    db.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
    db.execute("INSERT INTO room_master_table VALUES (42, ?)", (schema["identityHash"],))
    return db


def populate(db, schema):
    """Insert one row into every table, respecting insertion order for foreign keys."""
    # Parents before children: a table whose name appears in another's FK must be filled first.
    entities = sorted(schema["entities"], key=lambda e: len(e.get("foreignKeys", [])))
    inserted = {}
    for entity in entities:
        table = entity["tableName"]
        columns, values = [], []
        for field in entity["fields"]:
            value = sample_value(field, table)
            if value is None and field["columnName"] == "id":
                continue                              # omit so AUTOINCREMENT fires
            columns.append(field["columnName"])
            values.append(value)
        # Point any foreign key at the row we already inserted into the parent table.
        for fk in entity.get("foreignKeys", []):
            parent_id = inserted.get(fk["table"])
            if parent_id is not None:
                for column in fk["columns"]:
                    values[columns.index(column)] = parent_id
        db.execute("INSERT INTO `%s` (%s) VALUES (%s)" %
                   (table, ",".join("`%s`" % c for c in columns), ",".join("?" * len(columns))),
                   values)
        inserted[table] = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    db.commit()
    return inserted


def snapshot(db, schema):
    """Every row of every table in [schema], on THAT schema's columns only -- so a table that later
    gains columns is still compared like-for-like on the columns it already had."""
    return {e["tableName"]: db.execute(
                "SELECT %s FROM `%s` ORDER BY id" % (
                    ",".join("`%s`" % f["columnName"] for f in e["fields"]), e["tableName"])
            ).fetchall()
            for e in schema["entities"]}


def check_matches_schema(db, schema, version):
    """The migrated database vs the exported schema: what Room validates when it opens it."""
    for entity in schema["entities"]:
        table = entity["tableName"]
        actual = {row[1]: (row[2], row[3])
                  for row in db.execute("PRAGMA table_info(`%s`)" % table).fetchall()}
        expected = {f["columnName"]: (f["affinity"], 1 if f.get("notNull", False) else 0)
                    for f in entity["fields"]}
        check("`%s` columns match the v%d schema Room will validate" % (table, version),
              actual == expected, "expected %s\n        got      %s" % (expected, actual))
        actual_fks = sorted((r[2], r[3], r[4], r[6]) for r in
                            db.execute("PRAGMA foreign_key_list(`%s`)" % table).fetchall())
        expected_fks = sorted((fk["table"], column, referenced, fk["onDelete"])
                              for fk in entity.get("foreignKeys", [])
                              for column, referenced in zip(fk["columns"], fk["referencedColumns"]))
        check("`%s` foreign keys match v%d" % (table, version), actual_fks == expected_fks,
              "expected %s\n        got      %s" % (expected_fks, actual_fks))
        actual_idx = sorted(r[1] for r in db.execute("PRAGMA index_list(`%s`)" % table).fetchall()
                            if not r[1].startswith("sqlite_autoindex"))
        expected_idx = sorted(i["name"] for i in entity.get("indices", []))
        check("`%s` indices match v%d" % (table, version), actual_idx == expected_idx,
              "expected %s\n        got      %s" % (expected_idx, actual_idx))


def verify_hop(source, from_version, to_version):
    print("\n=== v%d -> v%d ===" % (from_version, to_version))
    old, new = load(from_version), load(to_version)
    old_tables = {e["tableName"]: e for e in old["entities"]}
    new_tables = {e["tableName"]: e for e in new["entities"]}

    statements = migration_statements(source, from_version, to_version)
    if statements is None:
        check("MIGRATION_%d_%d exists in Migrations.kt" % (from_version, to_version), False)
        return
    check("migration is registered in ALL_MIGRATIONS",
          "MIGRATION_%d_%d" % (from_version, to_version) in
          source[source.index("val ALL_MIGRATIONS"):])
    print("  SQL: " + "\n       ".join(statements))

    # --- 1. Every statement is one of the allowed additive forms. ---
    for statement in statements:
        stripped = FK_ACTION.sub("", statement).upper()
        for word in DESTRUCTIVE:
            check("no %s in: %s" % (word, statement[:64]),
                  not re.search(r"\b%s\b" % word, stripped))

    added_columns = {}                        # table -> {column: definition}
    created_tables = set()
    for statement in statements:
        kind = classify(statement)
        if kind[0] == "add_column":
            _, table, column, definition = kind
            check("ADD COLUMN `%s`.`%s` targets a pre-existing table" % (table, column),
                  table in old_tables)
            check("`%s`.`%s` is a column the old table did not have" % (table, column),
                  table in old_tables and column not in fields_by_name(old_tables[table]))
            not_null = "NOT NULL" in definition.upper()
            check("`%s`.`%s` is nullable, or NOT NULL with a DEFAULT" % (table, column),
                  not not_null or declared_default(definition) is not None, definition)
            added_columns.setdefault(table, {})[column] = definition
        elif kind[0] == "create_table":
            check("CREATE TABLE `%s` is for a new table" % kind[1],
                  kind[1] not in old_tables and kind[1] in new_tables)
            created_tables.add(kind[1])
        elif kind[0] == "create_index":
            check("CREATE INDEX `%s` is on a table this migration creates" % kind[1],
                  kind[2] in created_tables)
        else:
            check("statement is an allowed additive form", False, kind[1])

    # --- 2. Static schema diff. ---
    check("every v%d table survives into v%d" % (from_version, to_version),
          set(old_tables).issubset(set(new_tables)),
          "missing: %s" % (set(old_tables) - set(new_tables)))
    check("the migration creates exactly the tables v%d adds" % to_version,
          created_tables == set(new_tables) - set(old_tables),
          "created %s, schema adds %s" % (created_tables, set(new_tables) - set(old_tables)))
    for table, old_entity in old_tables.items():
        new_entity = new_tables.get(table)
        if new_entity is None:
            continue
        if table not in added_columns:
            check("`%s` definition byte-identical across the bump" % table,
                  json.dumps(old_entity, sort_keys=True) == json.dumps(new_entity, sort_keys=True))
            continue
        old_fields, new_fields = fields_by_name(old_entity), fields_by_name(new_entity)
        check("`%s` keeps every old column byte-identical" % table,
              all(json.dumps(f, sort_keys=True) == json.dumps(new_fields.get(name), sort_keys=True)
                  for name, f in old_fields.items()))
        check("`%s` gains exactly the columns the migration adds" % table,
              set(new_fields) - set(old_fields) == set(added_columns[table]),
              "schema adds %s, migration adds %s" % (sorted(set(new_fields) - set(old_fields)),
                                                    sorted(added_columns[table])))
        for key in ("primaryKey", "foreignKeys", "indices"):
            check("`%s` %s unchanged" % (table, key),
                  json.dumps(old_entity.get(key), sort_keys=True) ==
                  json.dumps(new_entity.get(key), sort_keys=True))
        for column, definition in added_columns[table].items():
            field = new_fields.get(column, {})
            check("`%s`.`%s` ADD COLUMN type and nullability match the schema" % (table, column),
                  field.get("affinity") == definition.split()[0].upper() and
                  bool(field.get("notNull", False)) == ("NOT NULL" in definition.upper()),
                  "schema %s vs migration '%s'" % (field, definition))

    # --- 3. Live migration of a populated database. ---
    db = create_database(old)
    populate(db, old)
    before = snapshot(db, old)

    for statement in statements:
        db.execute(statement)
    db.commit()

    after = snapshot(db, old)
    for table in old_tables:
        check("`%s` rows survive unchanged on their old columns" % table,
              before[table] == after[table],
              "before=%s\n        after= %s" % (before[table], after[table]))
    for table, columns in added_columns.items():
        for column, definition in columns.items():
            values = [r[0] for r in db.execute("SELECT `%s` FROM `%s`" % (column, table)).fetchall()]
            expected = declared_default(definition)
            check("existing `%s` rows read %r in the new `%s`" % (table, expected, column),
                  len(values) > 0 and all(v == expected for v in values), "got %s" % values)
    check("foreign keys still resolve",
          db.execute("PRAGMA foreign_key_check").fetchall() == [])

    added = set(new_tables) - set(old_tables)
    for table in added:
        check("new table `%s` exists and is empty" % table,
              db.execute("SELECT COUNT(*) FROM `%s`" % table).fetchone()[0] == 0)

    # --- 4. The result must match what Room validates against at open time. ---
    check_matches_schema(db, new, to_version)

    for table in added:
        created = db.execute("SELECT sql FROM sqlite_master WHERE name=?", (table,)).fetchone()[0]
        expected_sql = create_sql(new_tables[table]).replace("IF NOT EXISTS ", "")
        check("`%s` DDL equals Room's own createSql" % table,
              created.replace(" ", "") == expected_sql.replace(" ", ""),
              "got:      %s\n        expected: %s" % (created, expected_sql))
        for index in new_tables[table].get("indices", []):
            row = db.execute("SELECT sql FROM sqlite_master WHERE name=?", (index["name"],)).fetchone()
            expected_index = index_sql(index, table).replace("IF NOT EXISTS ", "")
            check("index `%s` DDL equals Room's own createSql" % index["name"],
                  row is not None and row[0].replace(" ", "") == expected_index.replace(" ", ""),
                  "got:      %s\n        expected: %s" % (row and row[0], expected_index))

    # --- 5. The new table must actually accept the rows the app will write. ---
    if "survey_tracks" in added:
        db.execute("INSERT INTO survey_tracks (label, startedAtMillis, endedAtMillis, pointCount, "
                   "points) VALUES (?,?,?,?,?)",
                   ("Survey 14 Nov 2023", 1700000000000, 1700000600000, 2,
                    "12.9716,77.5946,1700000000000;12.9718,77.5948,1700000003000"))
        db.execute("INSERT INTO survey_tracks (label, startedAtMillis, endedAtMillis, pointCount, "
                   "points) VALUES (?,?,?,?,?)",
                   ("In progress", 1700000700000, None, 0, ""))
        db.commit()
        rows = db.execute("SELECT id, endedAtMillis, pointCount, points FROM survey_tracks "
                          "ORDER BY id").fetchall()
        check("a completed track round-trips", rows[0][1] == 1700000600000 and rows[0][2] == 2
              and rows[0][3].count(";") == 1)
        check("an unfinished track keeps a NULL endedAtMillis", rows[1][1] is None)
        check("empty geometry is storable, not NULL", rows[1][3] == "")
        check("autoincrement issued distinct ids", rows[0][0] != rows[1][0])
    if "removed_trees" in added:
        db.execute("INSERT INTO removed_trees (latitude, longitude, officerLatitude, "
                   "officerLongitude, officerAccuracyMeters, proximityMeters, markedAtMillis) "
                   "VALUES (?,?,?,?,?,?,?)",
                   (12.9716, 77.5946, 12.9717, 77.5947, 4.5, 15.3, 1700000000002))
        db.execute("INSERT INTO removed_trees (latitude, longitude, markedAtMillis) VALUES (?,?,?)",
                   (12.98, 77.60, 1700000000003))
        db.commit()
        rows = db.execute("SELECT proximityMeters, officerLatitude FROM removed_trees "
                          "ORDER BY id").fetchall()
        check("a field-marked row round-trips", rows[0] == (15.3, 12.9717))
        check("a desk-marked row keeps NULL officer fields", rows[1] == (None, None))
    if "cluster_members" in added:
        verify_cluster_members(db)
    db.close()


def insert_member(db, session_id, member_index, inherited=0, species=None):
    db.execute(
        "INSERT INTO cluster_members (sessionId, memberIndex, ellipseCenterXPixels, "
        "ellipseCenterYPixels, ellipseRadiusXPixels, ellipseRadiusYPixels, distanceMeters, "
        "distanceMethod, bearingDegrees, canopyDiameterHorizontalMeters, "
        "canopyDiameterVerticalMeters, canopyDiameterMeters, projectedLatitude, projectedLongitude, "
        "polygonVertices, areaSquareMeters, treeClass, classSource, speciesScientificName, "
        "speciesInherited) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (session_id, member_index, 1200.5, 800.25, 140.0, 160.0, 11.75, "ARCORE_DEPTH_IMAGE",
         131.2, 5.1, 5.9, 5.5, 12.97175, 77.59482,
         "12.9716,77.5946;12.9716,77.5948;12.9718,77.5948", 23.4, "PALM", "MANUAL", species,
         inherited))


def verify_cluster_members(db):
    """The new child table accepts what the app writes, and behaves like a child table."""
    session_id = db.execute("SELECT id FROM sessions ORDER BY id LIMIT 1").fetchone()[0]
    insert_member(db, session_id, 2)
    insert_member(db, session_id, 3, inherited=1, species="Azadirachta indica")
    db.commit()
    rows = db.execute("SELECT memberIndex, distanceMethod, polygonVertices, speciesInherited, "
                      "classConfirmedAtMillis FROM cluster_members ORDER BY memberIndex").fetchall()
    check("member rows round-trip", [r[0] for r in rows] == [2, 3] and
          rows[0][1] == "ARCORE_DEPTH_IMAGE" and rows[0][2].count(";") == 2)
    check("speciesInherited stores 0/1", (rows[0][3], rows[1][3]) == (0, 1))
    check("nullable member columns accept NULL", rows[0][4] is None)
    try:
        insert_member(db, session_id, 2)
        duplicate_rejected = False
    except sqlite3.IntegrityError:
        duplicate_rejected = True
    check("(sessionId, memberIndex) is unique", duplicate_rejected)
    try:
        insert_member(db, session_id + 999, 2)
        orphan_rejected = False
    except sqlite3.IntegrityError:
        orphan_rejected = True
    check("a member must belong to an existing session", orphan_rejected)
    # The attempts row populate() made points at this session too; the cascade takes both.
    db.execute("DELETE FROM sessions WHERE id = ?", (session_id,))
    db.commit()
    check("deleting the session cascades to its members",
          db.execute("SELECT COUNT(*) FROM cluster_members").fetchone()[0] == 0)


def verify_chain(source, versions):
    """Oldest -> newest in ONE database: the path a device takes when it skipped releases."""
    first, last = versions[0], versions[-1]
    print("\n=== chain v%d -> v%d ===" % (first, last))
    oldest = load(first)
    db = create_database(oldest)
    populate(db, oldest)
    before = snapshot(db, oldest)
    for from_version, to_version in zip(versions, versions[1:]):
        for statement in migration_statements(source, from_version, to_version) or []:
            db.execute(statement)
    db.commit()
    after = snapshot(db, oldest)
    for table in before:
        check("v%d `%s` rows survive the whole chain" % (first, table), before[table] == after[table],
              "before=%s\n        after= %s" % (before[table], after[table]))
    check_matches_schema(db, load(last), last)
    check("foreign keys still resolve after the chain",
          db.execute("PRAGMA foreign_key_check").fetchall() == [])
    db.close()


def main():
    source = io.open(MIGRATIONS_KT, encoding="utf-8").read()
    versions = sorted(int(f[:-5]) for f in os.listdir(SCHEMA_DIR) if f.endswith(".json"))
    print("Exported schema versions: %s" % versions)
    for from_version, to_version in zip(versions, versions[1:]):
        verify_hop(source, from_version, to_version)
    if len(versions) > 2:
        verify_chain(source, versions)

    print()
    if failures:
        print("FAILED (%d):" % len(failures))
        for f in failures:
            print("  - %s" % f)
        sys.exit(1)
    print("ALL CHECKS PASSED across %d migration(s) and the v%d -> v%d chain - every upgrade is "
          "additive and preserves rows." % (len(versions) - 1, versions[0], versions[-1]))


if __name__ == "__main__":
    main()
