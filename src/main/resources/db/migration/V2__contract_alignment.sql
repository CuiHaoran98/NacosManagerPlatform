-- t6 前后端联调契约对齐（doc/ncg-api-contract.md 缺口1/§4.5、详细设计 v1.4 §4.5/§5.2）：
-- 1) ncg_config_mapping.format 固化列：纳管时按 dataId 后缀写入，此后不可变（MUST-15）；
--    迁移回填：.properties→PROPERTIES、.yaml/.yml→YAML、.json→JSON、其余→TEXT（TEXT 默认只读）。
-- 2) ncg_batch.mode：区分「组批发布」（RELEASE，既有 changeNos 模型，保持兼容）与
--    「子草稿批量」（DRAFT，契约缺口5：每个 DataId 生成 ChangeRequest 子草稿）。

ALTER TABLE ncg_config_mapping ADD COLUMN format varchar(16);

UPDATE ncg_config_mapping SET format = CASE
    WHEN lower(data_id) LIKE '%.properties' THEN 'PROPERTIES'
    WHEN lower(data_id) LIKE '%.yaml' OR lower(data_id) LIKE '%.yml' THEN 'YAML'
    WHEN lower(data_id) LIKE '%.json' THEN 'JSON'
    ELSE 'TEXT'
END;

ALTER TABLE ncg_config_mapping ALTER COLUMN format SET NOT NULL;

ALTER TABLE ncg_batch ADD COLUMN mode varchar(16) NOT NULL DEFAULT 'RELEASE';
