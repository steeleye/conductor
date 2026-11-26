-- One-off: drop the history row for data migration V13.2 so validation passes with
-- conductor.postgres.applyDataMigrations=false. Safe to rerun; remove once deployed.
DO $$
BEGIN
  IF to_regclass('"${flyway:defaultSchema}"."${flyway:table}"') IS NOT NULL THEN
    DELETE FROM "${flyway:defaultSchema}"."${flyway:table}" WHERE version = '13.2';
  END IF;
END $$;
