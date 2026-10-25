-- sql/131_v1126_disable_dead_workflow_menus.sql
-- ============================================================
-- v1.1.126: 停用 /workflow「设备安全」死项 (M 顶层 + 4 C 型子节点)
-- ------------------------------------------------------------
-- 背景:
--   v1.1.79 sql/50 建的「设备安全」工作流模块 (/workflow 顶层 M + 4 个 C 型子节点:
--   /workflow/tenant 租客管理, /workflow/maintain 设备维保记录, /workflow/safety 安全检查记录,
--   /workflow/emergency 应急预案) 在左侧导航已被 v1.1.90 的 /xk/maintenance + /xk/safety
--   新 XK 页取代, 且 router/index.js 里已无 /workflow 及 4 个子 path 的任何路由。
--   这 5 行是"配置了但前端根本走不到"的死项, 堆在 #/system/menu 菜单管理页里显得多余。
--
-- 为什么停用 (status=0) 而不是删除 (deleted=1):
--   - /workflow 顶层 M 节点被 13 个角色 sys_role_menu 授权, 删行会打断授权关系;
--   - 子节点 perms work:maintain:list / work:safety:list 正被 /xk/maintenance、/xk/safety
--     两个真实导航项复用 (router line 76/77), 停用 sys_menu.status 不影响已落库的
--     sys_role_menu 授权, 也不影响 router 门禁 (门禁看 perms 字符串而非 sys_menu 行)。
--   - status=0 可逆: 翻回 1 即恢复。
--
-- 影响面验证 (菜单管理页):
--   - Menu.vue loadData 走 menuApi.list() → 后端 M 型默认查 status=1, 停用的 5 行会从列表消失;
--   - 左侧导航是 MainLayout 硬编码 15 项, 本就不含 /workflow, 无变化;
--   - 角色授权树 Role.vue: /workflow 节点变灰 (status=0), 但不影响 work:*:list 实际授权。
--
-- 幂等性: 纯状态位 UPDATE, WHERE 带 status=1 守门, 连跑 N 次结果一致。
-- 期望: 5 行 status=1 → 0。验证 SELECT 见文末。
-- ============================================================

UPDATE sys_menu
SET status = 0, is_visible = 0
WHERE id IN (
  2090345792472715354,  -- /workflow          M 顶层「设备安全」
  2090345792472715355,  -- /workflow/tenant   C 租客管理
  2090345792472715356,  -- /workflow/maintain C 设备维保记录
  2090345792472715357,  -- /workflow/safety   C 安全检查记录
  2090345792472715358   -- /workflow/emergency C 应急预案
)
AND status = 1;

-- ---------- 验证 (期望: 5 行全 status=0) ----------
-- SELECT id, menu_name, path, menu_type, is_visible, status FROM sys_menu
-- WHERE id IN (2090345792472715354,2090345792472715355,2090345792472715356,
--              2090345792472715357,2090345792472715358);
-- 期望输出 5 行, status 列全 0。
