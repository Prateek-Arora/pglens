-- When an applied recommendation became advice again because its index was dropped (ADR-0051).
-- If the index is then built again, the "before" of its before/after comparison starts here, so the
-- time the earlier index existed never counts as "before". NULL for advice that was never applied.
ALTER TABLE recommendations ADD COLUMN active_since TIMESTAMPTZ;
