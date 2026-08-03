# 排班日历

一款基于 Jetpack Compose 开发的 Android 排班与薪资管理应用，帮助倒班/轮班工作者记录班次、打卡考勤、统计工时与薪资。

## 功能特性

### 日历排班
- 月视图展示每日班次，支持滑动切换月份
- 自定义班次管理（名称、颜色、时间段）
- 批量排班、复制排班、清除排班
- 排班规则自动应用（循环排班）
- 附加状态标记（请假、调休、休息）
- 系统日历同步（日程/纪念日）

### 上下班打卡
- 快捷打卡按钮（桌面小组件 & 快捷方式）
- 漏打卡检测与补录
- 早到/晚退加班自动识别与确认
- 跨午夜班次自动处理

### 工时统计
- 正常/加班/周末/节假日工时分类统计
- 迟到/早退次数统计与超限提醒
- 每日工时柱状图 & 月度趋势图
- 备注与补贴/扣款记录

### 薪资计算
- 基础底薪 + 绩效自动核算
- 正常/加班/周末/节假日工资分项计算
- 补贴与扣款管理
- 社保/公积金/个税扣除
- 薪资构成饼图 & 月薪趋势折线图
- 当月预计薪资实时显示

### 待办中心
- 漏打卡提醒
- 加班待确认事项
- 日程与纪念日管理
- 法定节假日展示（含调休）

### 其他
- 桌面小组件（快捷打卡 + 排班日历）
- 上下班打卡提醒
- 纪念日提醒
- 数据自动备份
- Material You 动态主题

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 架构 | MVVM + Hilt 依赖注入 |
| 数据库 | Room |
| 导航 | Navigation Compose（类型安全路由） |
| 异步 | Kotlin Coroutines + Flow |
| 桌面组件 | Glance |
| 最低支持 | Android 8.0 (API 26) |

## 构建

```bash
# 调试版
./gradlew assembleDebug

# 发布版
./gradlew assembleRelease
```

发布版签名配置存储在 `local.properties` 中（不纳入版本控制）：

```properties
RELEASE_STORE_FILE=/path/to/keystore
RELEASE_STORE_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_password
```

## 许可证

本项目仅供学习交流使用。
