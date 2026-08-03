---
kind: external_dependency
name: 本地数据库 ORM 框架
slug: room-database
category: external_dependency
category_hints:
    - sdk_real_api
scope:
    - '**'
---

### Room 数据库
- **角色**: SQLite 的 ORM 抽象层，提供类型安全的数据库访问
- **集成点**: `AppDatabase.kt` 定义数据库实体和 DAO 接口
- **使用模式**: 使用 KSP 注解处理器生成代码，支持增量编译
- **数据迁移**: Schema 文件存储在 `build/schemas/` 目录，支持版本升级
- **验证**: 参考 Room 官方文档确认 DAO 方法和实体定义规范