-- ============================================================
-- 数据库重构脚本（Online-Ordering-System）
-- 说明：对原有 t_user / t_restaurant / t_dish / t_order / t_order_item /
--       t_review / t_address 进行字段补全，并新增骑手、购物车、用户偏好等表。
-- 目标：1) 骑手静态数据入库，去掉 OrderService 里的硬编码 VIRTUAL_RIDERS；
--       2) 补齐业务完整度，为后续 Agent 编排（工具化、记忆、断言校验）打地基。
-- 兼容：MySQL 5.7 / 8.0（utf8mb4）。
-- 注意：本脚本为「重建」语义，DROP 操作请确认无生产数据后再执行。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 数据库
CREATE DATABASE IF NOT EXISTS `takeaway_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `takeaway_db`;

-- ------------------------------------------------------------
-- 1. 用户表 t_user
-- 补全：头像、昵称、性别、会员等级、积分、状态、注册/更新时间
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user` (
  `user_id`       VARCHAR(32)  NOT NULL COMMENT '用户ID（UUID 去横线）',
  `username`      VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  `phone`         VARCHAR(20)  NOT NULL COMMENT '手机号（登录凭证）',
  `avatar`        VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `gender`        TINYINT      DEFAULT 0 COMMENT '性别 0未知 1男 2女',
  `member_level`  TINYINT      DEFAULT 0 COMMENT '会员等级 0普通 1银卡 2金卡 3黑金',
  `points`        INT          DEFAULT 0 COMMENT '积分',
  `status`        TINYINT      DEFAULT 1 COMMENT '状态 1正常 0封禁',
  `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. 店铺表 t_restaurant
-- 补全：菜系、营业时间、起送价、配送费、配送半径、月售、图片、状态字段
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_restaurant`;
CREATE TABLE `t_restaurant` (
  `restaurant_id`   VARCHAR(32)   NOT NULL COMMENT '店铺ID',
  `name`            VARCHAR(128)  NOT NULL COMMENT '店铺名',
  `cuisine`         VARCHAR(32)   DEFAULT NULL COMMENT '菜系（川菜/粤菜/快餐/奶茶...）',
  `rating`          DECIMAL(2,1)  DEFAULT 5.0 COMMENT '评分',
  `address`         VARCHAR(255)  DEFAULT NULL COMMENT '店铺地址',
  `logo`            VARCHAR(255)  DEFAULT NULL COMMENT '店铺logo URL',
  `open_time`       TIME          DEFAULT '09:00:00' COMMENT '营业开始时间',
  `close_time`      TIME          DEFAULT '22:00:00' COMMENT '营业结束时间',
  `min_order`       DECIMAL(10,2) DEFAULT 0 COMMENT '起送价',
  `delivery_fee`    DECIMAL(10,2) DEFAULT 0 COMMENT '配送费',
  `delivery_radius` DECIMAL(10,2) DEFAULT 3000 COMMENT '配送半径（米）',
  `monthly_sales`   INT           DEFAULT 0 COMMENT '月售单量',
  `audit_status`    VARCHAR(16)   DEFAULT 'PENDING' COMMENT '审核状态 PENDING/APPROVED/REJECTED',
  `status`          TINYINT       DEFAULT 1 COMMENT '营业状态 1营业 0休息',
  `created_at`      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`restaurant_id`),
  KEY `idx_audit` (`audit_status`),
  KEY `idx_rating` (`rating`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺表';

-- ------------------------------------------------------------
-- 3. 菜品表 t_dish
-- 补全：上下架状态、是否招牌菜、是否辣、销量冗余、口味标签、图片
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_dish`;
CREATE TABLE `t_dish` (
  `dish_id`       VARCHAR(32)   NOT NULL COMMENT '菜品ID',
  `restaurant_id` VARCHAR(32)   NOT NULL COMMENT '所属店铺ID',
  `name`          VARCHAR(128)  NOT NULL COMMENT '菜品名',
  `category`      VARCHAR(32)   DEFAULT NULL COMMENT '分类（主食/热菜/饮品...）',
  `price`         DECIMAL(10,2) NOT NULL COMMENT '售价',
  `description`   VARCHAR(512)  DEFAULT NULL COMMENT '描述',
  `image`         VARCHAR(255)  DEFAULT NULL COMMENT '图片URL',
  `current_stock` INT           DEFAULT 0 COMMENT '当前库存',
  `safety_stock`  INT           DEFAULT 10 COMMENT '安全库存（低于此值预警）',
  `sales`         INT           DEFAULT 0 COMMENT '累计销量',
  `is_signature`  TINYINT       DEFAULT 0 COMMENT '是否招牌菜 1是 0否',
  `is_spicy`      TINYINT       DEFAULT 0 COMMENT '是否辣 1是 0否',
  `tags`          VARCHAR(255)  DEFAULT NULL COMMENT '口味标签（逗号分隔：微辣/清淡/素食...）',
  `status`        TINYINT       DEFAULT 1 COMMENT '上架状态 1上架 0下架',
  `created_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`dish_id`),
  KEY `idx_restaurant` (`restaurant_id`),
  KEY `idx_sales` (`sales`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品表';

-- ------------------------------------------------------------
-- 4. 骑手表 t_rider  【新增 · 核心需求】
-- 把 OrderService 里硬编码的 VIRTUAL_RIDERS 迁到这里
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_rider`;
CREATE TABLE `t_rider` (
  `rider_id`         VARCHAR(32)  NOT NULL COMMENT '骑手ID',
  `name`             VARCHAR(64)  NOT NULL COMMENT '骑手姓名',
  `phone`            VARCHAR(20)  NOT NULL COMMENT '骑手手机号',
  `avatar`           VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `id_card_no`       VARCHAR(32)  DEFAULT NULL COMMENT '身份证号（脱敏存储）',
  `vehicle_type`     VARCHAR(16)  DEFAULT 'ELECTRIC' COMMENT '车辆类型 ELECTRIC/MOTOR/BIKE',
  `vehicle_no`       VARCHAR(32)  DEFAULT NULL COMMENT '车牌号',
  `rating`           DECIMAL(2,1) DEFAULT 5.0 COMMENT '骑手评分',
  `delivered_count`  INT          DEFAULT 0 COMMENT '累计完成单量',
  `status`           VARCHAR(16)  DEFAULT 'OFFLINE' COMMENT '状态 OFFLINE/ONLINE/DELIVERING/REST',
  `current_lng`      DECIMAL(10,6) DEFAULT NULL COMMENT '当前经度',
  `current_lat`      DECIMAL(10,6) DEFAULT NULL COMMENT '当前纬度',
  `current_order_id` VARCHAR(32)  DEFAULT NULL COMMENT '当前配送中的订单ID（无则空）',
  `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '入职时间',
  `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`rider_id`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='骑手表';

-- 初始骑手数据（替代原 VIRTUAL_RIDERS 两个假骑手，补充为 4 个）
INSERT INTO `t_rider` (`rider_id`, `name`, `phone`, `vehicle_type`, `rating`, `delivered_count`, `status`) VALUES
  ('rider_001', '张小哥', '13911112222', 'ELECTRIC', 4.8, 1286, 'ONLINE'),
  ('rider_002', '王大叔', '13688889999', 'MOTOR',   4.6,  982, 'ONLINE'),
  ('rider_003', '李师傅', '13700001111', 'ELECTRIC', 4.9, 2031, 'ONLINE'),
  ('rider_004', '赵小飞', '13522223333', 'BIKE',     4.7,  455, 'REST');

-- ------------------------------------------------------------
-- 5. 订单表 t_order
-- 补全：接单时间、发货时间、完成时间、骑手ID、配送状态、取消字段、幂等键
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_order`;
CREATE TABLE `t_order` (
  `order_id`         VARCHAR(32)   NOT NULL COMMENT '订单ID',
  `order_no`         VARCHAR(64)   NOT NULL COMMENT '订单号（展示用）',
  `user_id`          VARCHAR(32)   NOT NULL COMMENT '下单用户',
  `restaurant_id`    VARCHAR(32)   NOT NULL COMMENT '店铺ID',
  `total_amount`     DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
  `status`           VARCHAR(24)   NOT NULL DEFAULT 'UNPAID' COMMENT '状态 UNPAID/PREPARING/DELIVERING/COMPLETED/CANCELLED/DELIVERY_EXCEPTION',
  `address`          VARCHAR(255)  DEFAULT NULL COMMENT '收货地址（冗余快照）',
  `rider_id`         VARCHAR(32)   DEFAULT NULL COMMENT '接单骑手ID',
  `rider_name`       VARCHAR(64)   DEFAULT NULL COMMENT '骑手姓名（快照）',
  `rider_phone`      VARCHAR(20)   DEFAULT NULL COMMENT '骑手电话（快照）',
  `pay_method`       VARCHAR(16)   DEFAULT NULL COMMENT '支付方式 WECHAT/ALIPAY/BANK_CARD',
  `paid_at`          DATETIME      DEFAULT NULL COMMENT '支付时间',
  `expire_time`      DATETIME      DEFAULT NULL COMMENT '支付过期时间（10分钟）',
  `accepted_at`      DATETIME      DEFAULT NULL COMMENT '商家接单时间',
  `delivered_at`     DATETIME      DEFAULT NULL COMMENT '发货（骑手取餐）时间',
  `completed_at`     DATETIME      DEFAULT NULL COMMENT '完成时间',
  `cancel_reason`    VARCHAR(255)  DEFAULT NULL COMMENT '取消原因',
  `cancelled_at`     DATETIME      DEFAULT NULL COMMENT '取消时间',
  `idempotency_key`  VARCHAR(64)   DEFAULT NULL COMMENT '幂等键（防重复下单）',
  `created_at`       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `updated_at`       DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_idempotency` (`idempotency_key`),
  KEY `idx_user` (`user_id`),
  KEY `idx_restaurant` (`restaurant_id`),
  KEY `idx_status` (`status`),
  KEY `idx_rider` (`rider_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- ------------------------------------------------------------
-- 6. 订单明细表 t_order_item
-- 基本保持，补菜品名快照
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_order_item`;
CREATE TABLE `t_order_item` (
  `item_id`     VARCHAR(32)   NOT NULL COMMENT '明细ID',
  `order_id`    VARCHAR(32)   NOT NULL COMMENT '订单ID',
  `dish_id`     VARCHAR(32)   NOT NULL COMMENT '菜品ID',
  `dish_name`   VARCHAR(128)  DEFAULT NULL COMMENT '菜品名（下单时快照，防改名）',
  `quantity`    INT           NOT NULL DEFAULT 1 COMMENT '数量',
  `subtotal`    DECIMAL(10,2) NOT NULL COMMENT '小计',
  PRIMARY KEY (`item_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_dish` (`dish_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- ------------------------------------------------------------
-- 7. 评价表 t_review
-- 补全：评分区间约束、图片、商家回复、匿名标记
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_review`;
CREATE TABLE `t_review` (
  `review_id`     VARCHAR(32)   NOT NULL COMMENT '评价ID',
  `order_id`      VARCHAR(32)   NOT NULL COMMENT '订单ID',
  `restaurant_id` VARCHAR(32)   NOT NULL COMMENT '店铺ID',
  `dish_id`       VARCHAR(32)   DEFAULT NULL COMMENT '菜品ID（菜品维度评价时非空，餐厅整体评价为空）',
  `user_id`       VARCHAR(32)   NOT NULL COMMENT '用户ID',
  `username`      VARCHAR(64)   DEFAULT NULL COMMENT '用户名快照',
  `rating`        TINYINT       NOT NULL COMMENT '评分 1-5',
  `comment`       VARCHAR(1024) DEFAULT NULL COMMENT '评价内容',
  `images`        VARCHAR(1024) DEFAULT NULL COMMENT '晒图（逗号分隔URL）',
  `is_anonymous`  TINYINT       DEFAULT 0 COMMENT '是否匿名 1是 0否',
  `reply`         VARCHAR(1024) DEFAULT NULL COMMENT '商家回复',
  `created_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
  PRIMARY KEY (`review_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_restaurant` (`restaurant_id`),
  KEY `idx_dish` (`dish_id`),
  CONSTRAINT `chk_rating` CHECK (`rating` BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价表';

-- ------------------------------------------------------------
-- 8. 收货地址表 t_address
-- 补全：标签、默认地址、经纬度、逻辑删除
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_address`;
CREATE TABLE `t_address` (
  `address_id`      VARCHAR(32)   NOT NULL COMMENT '地址ID',
  `user_id`         VARCHAR(32)   NOT NULL COMMENT '用户ID',
  `receiver_name`   VARCHAR(64)   NOT NULL COMMENT '收货人',
  `receiver_phone`  VARCHAR(20)   NOT NULL COMMENT '收货人电话',
  `detail_address`  VARCHAR(255)  NOT NULL COMMENT '详细地址',
  `tag`             VARCHAR(32)   DEFAULT NULL COMMENT '标签（家/公司/学校...）',
  `is_default`      TINYINT       DEFAULT 0 COMMENT '是否默认地址 1是 0否',
  `lng`             DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
  `lat`             DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
  `deleted`         TINYINT       DEFAULT 0 COMMENT '逻辑删除 1删除 0正常',
  `created_at`      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`address_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址表';

-- ------------------------------------------------------------
-- 9. 购物车表 t_cart 【新增】
-- 把前端 JS 内存里的 cart 落到服务端，多端一致、重启不丢
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_cart`;
CREATE TABLE `t_cart` (
  `cart_id`       VARCHAR(32) NOT NULL COMMENT '购物车项ID',
  `user_id`       VARCHAR(32) NOT NULL COMMENT '用户ID',
  `restaurant_id` VARCHAR(32) NOT NULL COMMENT '店铺ID',
  `dish_id`       VARCHAR(32) NOT NULL COMMENT '菜品ID',
  `quantity`      INT         NOT NULL DEFAULT 1 COMMENT '数量',
  `created_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  `updated_at`    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`cart_id`),
  UNIQUE KEY `uk_user_dish` (`user_id`, `dish_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';

-- ------------------------------------------------------------
-- 10. 用户偏好表 t_user_pref 【新增 · 为 Agent 长期记忆预留】
-- 供后续 agent 规划时前置注入（口味/忌口/客单价/常点店）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_user_pref`;
CREATE TABLE `t_user_pref` (
  `pref_id`         VARCHAR(32)  NOT NULL COMMENT '偏好ID',
  `user_id`         VARCHAR(32)  NOT NULL COMMENT '用户ID',
  `pref_key`        VARCHAR(64)  NOT NULL COMMENT '偏好键（taste/spicy/avg_price/fav_restaurant...）',
  `pref_value`      VARCHAR(512) DEFAULT NULL COMMENT '偏好值',
  `weight`          DECIMAL(8,4) DEFAULT 1.0 COMMENT '权重（用于多偏好融合排序）',
  `source`          VARCHAR(16)  DEFAULT 'EXPLICIT' COMMENT '来源 EXPLICIT(用户明示)/INFERRED(行为推断)',
  `updated_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`pref_id`),
  UNIQUE KEY `uk_user_key` (`user_id`, `pref_key`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好表';

-- ------------------------------------------------------------
-- 11. Agent 运行表 t_agent_run 【新增 · Agent 编排】
-- 记录一次完整对话/任务的编排过程，支持断点恢复、Trace 回放、成本统计
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_agent_run`;
CREATE TABLE `t_agent_run` (
  `run_id`      VARCHAR(32)  NOT NULL COMMENT '运行ID',
  `user_id`     VARCHAR(32)  DEFAULT NULL COMMENT '用户ID',
  `session_id`  VARCHAR(64)  DEFAULT NULL COMMENT '会话ID',
  `goal`        TEXT         COMMENT '用户原始意图',
  `status`      VARCHAR(24)  DEFAULT 'PENDING' COMMENT 'PENDING/PLANNING/EXECUTING/WAITING_HUMAN/DONE/FAILED',
  `plan_json`   TEXT         COMMENT '计划快照（JSON）',
  `result_json` TEXT         COMMENT '最终结果（JSON）',
  `error_msg`   VARCHAR(1024) DEFAULT NULL COMMENT '错误信息',
  `token_used`  INT          DEFAULT 0 COMMENT '累计 token 消耗',
  `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`run_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent运行表';

-- ------------------------------------------------------------
-- 12. Agent 步骤表 t_agent_step 【新增 · Agent 编排】
-- 计划中单个步骤的执行情况，是可回放、可恢复、可断言的最小单元
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_agent_step`;
CREATE TABLE `t_agent_step` (
  `step_id`     VARCHAR(32)  NOT NULL COMMENT '步骤ID',
  `run_id`      VARCHAR(32)  NOT NULL COMMENT '所属运行ID',
  `seq`         INT          DEFAULT 0 COMMENT '步骤序号',
  `tool_name`   VARCHAR(64)  DEFAULT NULL COMMENT '工具名',
  `args_json`   TEXT         COMMENT '入参（JSON）',
  `output_json` TEXT         COMMENT '出参（JSON）',
  `expect`      VARCHAR(255) DEFAULT NULL COMMENT '断言描述',
  `status`      VARCHAR(24)  DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED/SKIPPED',
  `attempt`     INT          DEFAULT 0 COMMENT '重试次数',
  `started_at`  DATETIME     DEFAULT NULL COMMENT '开始时间',
  `finished_at` DATETIME     DEFAULT NULL COMMENT '结束时间',
  `error_msg`   VARCHAR(1024) DEFAULT NULL COMMENT '错误信息',
  PRIMARY KEY (`step_id`),
  KEY `idx_run` (`run_id`),
  KEY `idx_tool` (`tool_name`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent步骤表';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 迁移备注
-- 1) 原 OrderService 里的 VIRTUAL_RIDERS 硬编码删除，改为从 t_rider 按
--    status='ONLINE' 且 current_order_id IS NULL 随机/就近挑选一名骑手，
--    并回写 rider_id 到 t_order。
-- 2) 原 OrderController 的 /api/orders/track 里的 dishName 通过 Dish 查询，
--    可改为直接读 t_order_item.dish_name 快照，减少一次关联查询。
-- 3) t_order 新增 accepted_at/delivered_at/completed_at，修复状态机缺
--    「商家接单」节点的问题。
-- 4) t_order.idempotency_key 用于 agent 下单幂等，后续 P2 阶段启用。
-- ============================================================
