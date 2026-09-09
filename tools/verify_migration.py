"""
Proves every shipped Room migration is additive and preserves existing rows.

Run with:  python tools/verify_migration.py

Uses Python's bundled sqlite3 -- the same engine Android ships -- so migrations can be verified
WITHOUT a connected device. That matters for this project specifically: the app is a documentation
tool holding field observations that cannot be re-collected, so "this upgrade does not destroy data"
needs to be checkable on every change, including in CI, not only when someone has a phone plugged in.

What it does, for each consecutive pair of exported schema versions:
  1. Recovers the migration's SQL from Migrations.kt itself, so it tests what ships, not a copy.
  2. Greps that SQL for destructive statements and for the names of pre-existing tables.
  3. Diffs the two exported schemas: the older version's tables must be present and byte-identical.
  4. Builds a real database at the OLD version, fills every table with representative rows,
     runs the migration, and checks every row is still there and unchanged.
  5. Compares the migrated database's PRAGMA table_info against the NEW exported schema -- the same
     comparison Room performs when opening the database on a user's phone.

The on-device equivalent is MigrationTest (androidTest), which additionally runs Room's own schema
validator. The two overlap but are not identical: this harness also greps the migration source and
diffs the schema JSON version-to-version.
"""
import io, json, os, re, sqlite3, sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_DIR = os.path.join(REPO, "app", "schemas", "com.gops.spatialmapper.data.AppDatabase")
MIGRATIONS_KT = os.path.join(REPO, "app", "src", "main", "java", "com", "gops", "spatialmapper",
                             "data", "Migrations.kt")

DESTRUCTIVE = ("DROP", "ALTER", "DELETE", "UPDATE")

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
    return {e["tableName"]: db.execute("SELECT * FROM `%s` ORDER BY id" % e["tableName"]).fetchall()
            for e in schema["entities"]}


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

    # --- 1. The migration cannot reach an existing table. ---
    combined = " ".join(statements).upper()
    for word in DESTRUCTIVE:
        check("no %s statement" % word, word not in combined)
    for table in old_tables:
        check("never names the pre-existing table `%s`" % table,
              table not in " ".join(statements))

    # --- 2. Static schema diff. ---
    check("every v%d table survives into v%d" % (from_version, to_version),
          set(old_tables).issubset(set(new_tables)),
          "missing: %s" % (set(old_tables) - set(new_tables)))
    for table in old_tables:
        check("`%s` definition byte-identical across the bump" % table,
              json.dumps(old_tables[table], sort_keys=True) ==
              json.dumps(new_tables[table], sort_keys=True))

    # --- 3. Live migration of a populated database. ---
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for entity in old["entities"]:
        db.execute(create_sql(entity))
    db.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
    db.execute("INSERT INTO room_master_table VALUES (42, ?)", (old["identityHash"],))
    populate(db, old)
    before = snapshot(db, old)

    for statement in statements:
        db.execute(statement)
    db.commit()

    after = snapshot(db, old)
    for table in old_tables:
        check("`%s` rows survive unchanged" % table, before[table] == after[table],
              "before=%s\n        after= %s" % (before[table], after[table]))
    check("foreign keys still resolve",
          db.execute("PRAGMA foreign_key_check").fetchall() == [])

    added = set(new_tables) - set(old_tables)
    for table in added:
        check("new table `%s` exists and is empty" % table,
              db.execute("SELECT COUNT(*) FROM `%s`" % table).fetchone()[0] == 0)

    # --- 4. The result must match what Room validates against at open time. ---
    for table, entity in new_tables.items():
        actual = {row[1]: (row[2], row[3])
                  for row in db.execute("PRAGMA table_info(`%s`)" % table).fetchall()}
        expected = {f["columnName"]: (f["affinity"], 1 if f.get("notNull", False) else 0)
                    for f in entity["fields"]}
        check("`%s` columns match the v%d schema Room will validate" % (table, to_version),
              actual == expected, "expected %s\n        got      %s" % (expected, actual))

    for table in added:
        created = db.execute("SELECT sql FROM sqlite_master WHERE name=?", (table,)).fetchone()[0]
        expected_sql = create_sql(new_tables[table]).replace("IF NOT EXISTS ", "")
        check("`%s` DDL equals Room's own createSql" % table,
              created.replace(" ", "") == expected_sql.replace(" ", ""),
              "got:      %s\n        expected: %s" % (created, expected_sql))

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
    db.close()


def main():
    source = io.open(MIGRATIONS_KT, encoding="utf-8").read()
    versions = sorted(int(f[:-5]) for f in os.listdir(SCHEMA_DIR) if f.endswith(".json"))
    print("Exported schema versions: %s" % versions)
    for from_version, to_version in zip(versions, versions[1:]):
        verify_hop(source, from_version, to_version)

    print()
    if failures:
        print("FAILED (%d):" % len(failures))
        for f in failures:
            print("  - %s" % f)
        sys.exit(1)
    print("ALL CHECKS PASSED across %d migration(s) - every upgrade is additive and preserves rows."
          % (len(versions) - 1))


if __name__ == "__main__":
    main()
