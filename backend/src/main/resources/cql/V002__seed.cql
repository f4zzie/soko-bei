-- Seed data. All unit conversion factors are PLACEHOLDERS (verified = false) and must be checked in the field.
-- Seed is idempotent: plain INSERTs are upserts.

INSERT INTO markets (market_id, name, county, lat, lng, created_at, updated_at) VALUES ('wakulima', 'Wakulima Market', 'Nairobi', -1.2833, 36.8167, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO markets (market_id, name, county, lat, lng, created_at, updated_at) VALUES ('marikiti', 'Marikiti Market', 'Nairobi', -1.2921, 36.8219, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO markets (market_id, name, county, lat, lng, created_at, updated_at) VALUES ('kongowea', 'Kongowea Market', 'Mombasa', -4.0276, 39.6679, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO markets (market_id, name, county, lat, lng, created_at, updated_at) VALUES ('kibuye', 'Kibuye Market', 'Kisumu', -0.1022, 34.7505, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO markets (market_id, name, county, lat, lng, created_at, updated_at) VALUES ('nakuru-main', 'Nakuru Main Market', 'Nakuru', -0.2833, 36.0667, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('maize', 'Maize', 'Mahindi', 'kg', ['standard'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('dry_beans', 'Dry beans', 'Maharagwe', 'kg', ['standard'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('tomatoes', 'Tomatoes', 'Nyanya', 'crate', ['standard', 'grade_a', 'grade_c'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('onions', 'Onions', 'Vitunguu', 'kg', ['standard', 'grade_a', 'grade_c'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('irish_potatoes', 'Irish potatoes', 'Viazi mviringo', 'bag_50kg', ['standard', 'grade_a', 'grade_c'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('cabbage', 'Cabbage', 'Kabichi', 'piece', ['standard'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('sukuma_wiki', 'Sukuma wiki (kale)', 'Sukuma wiki', 'bunch', ['standard'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('bananas', 'Bananas', 'Ndizi', 'bunch', ['standard'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('rice', 'Rice', 'Mchele', 'kg', ['standard'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');
INSERT INTO commodities (commodity_id, name_en, name_sw, default_unit, grades, created_at, updated_at) VALUES ('carrots', 'Carrots', 'Karoti', 'kg', ['standard'], '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('maize', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('maize', 'gorogoro', 1, 2.2, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('maize', 'debe', 1, 18, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('maize', 'bag_90kg', 1, 90, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('dry_beans', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('dry_beans', 'gorogoro', 1, 2.0, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('dry_beans', 'debe', 1, 16, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('dry_beans', 'bag_90kg', 1, 90, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('tomatoes', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('tomatoes', 'crate', 1, 60, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('tomatoes', 'debe', 1, 18, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('onions', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('onions', 'bag_50kg', 1, 50, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('onions', 'debe', 1, 15, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('onions', 'gorogoro', 1, 2.0, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('irish_potatoes', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('irish_potatoes', 'bag_50kg', 1, 50, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('irish_potatoes', 'debe', 1, 17, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('irish_potatoes', 'gorogoro', 1, 2.2, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('cabbage', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('cabbage', 'piece', 1, 1.5, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('cabbage', 'bag_50kg', 1, 50, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('sukuma_wiki', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('sukuma_wiki', 'bunch', 1, 0.4, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('sukuma_wiki', 'bag_50kg', 1, 50, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('bananas', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('bananas', 'bunch', 1, 15, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('bananas', 'piece', 1, 0.15, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('rice', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('rice', 'gorogoro', 1, 2.0, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('rice', 'bag_50kg', 1, 50, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('rice', 'bag_90kg', 1, 90, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('carrots', 'kg', 1, 1, '2026-01-01', true, 'exact');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('carrots', 'crate', 1, 50, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('carrots', 'bag_50kg', 1, 50, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
INSERT INTO commodity_units (commodity_id, unit, version, kg_per_unit, valid_from, verified, note) VALUES ('carrots', 'debe', 1, 16, '2026-01-01', false, 'PLACEHOLDER value, verify in the field');
