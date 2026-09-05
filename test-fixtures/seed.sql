-- Minimal single-table dataset for integration-testing the report pipeline
-- (report loading -> external .jrtx template resolution -> external image resolution
-- -> SQL execution -> PDF/XLSX export), without needing a copy of the real database.
--
-- All names/emails below are fictional. Point a Postgres JDBC connection at a
-- database seeded with this script, then fill test-fixtures/reports/members/report.jrxml
-- (shared assets in test-fixtures/reports/_shared/) against it.

CREATE TABLE members (
    id         SERIAL PRIMARY KEY,
    first_name TEXT NOT NULL,
    last_name  TEXT NOT NULL,
    email      TEXT,
    joined_on  DATE NOT NULL
);

-- Explicit ids (rather than relying on the SERIAL default) so this script stays
-- idempotent: a test can run it inside a transaction it rolls back afterwards
-- without ids drifting on every run (Postgres sequences aren't transactional).
INSERT INTO members (id, first_name, last_name, email, joined_on) VALUES
    (1,  'Anna',    'Bauer',     'anna.bauer@example.org',     '2018-03-12'),
    (2,  'Bastian', 'Fischer',   'bastian.fischer@example.org','2019-07-01'),
    (3,  'Clara',   'Hoffmann',  'clara.hoffmann@example.org', '2020-01-22'),
    (4,  'David',   'Koch',      NULL,                         '2020-11-05'),
    (5,  'Elena',   'Lindner',   'elena.lindner@example.org',  '2021-04-18'),
    (6,  'Finn',    'Meyer',     'finn.meyer@example.org',     '2021-09-30'),
    (7,  'Greta',   'Neumann',   'greta.neumann@example.org',  '2022-02-14'),
    (8,  'Hannes',  'Ott',       NULL,                         '2022-06-09'),
    (9,  'Ida',     'Petersen',  'ida.petersen@example.org',   '2023-05-27'),
    (10, 'Jonas',   'Richter',   'jonas.richter@example.org',  '2024-08-03');
