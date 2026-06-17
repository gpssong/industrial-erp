# 工业 ERP - 移动端 (uni-app Vue3)

## 兼容性
- ✅ Android (5.0+)
- ✅ iOS (10+)
- ✅ 微信小程序
- ✅ 微信公众号 (H5)
- ✅ H5

## 启动

```bash
# 安装 HBuilderX (推荐) 或 CLI
# 在 HBuilderX 中: 文件 -> 打开目录 -> app/
# 菜单: 运行 -> 运行到手机或模拟器 -> 选择设备
# 或: 发行 -> 原生 APP-云打包

# CLI 方式
npx degit dcloudio/uni-preset-vue my-project
# 把本目录内容覆盖过去
npm install
npm run dev:h5
npm run dev:mp-weixin
```

## 功能模块
- 工作台 (KPI + 业务快捷)
- 库存查询 (扫码/手动)
- 手机开单 (快速销售出库草稿)
- 扫码入库 / 出库
- 外勤盘点
- 经营简报
- 我的

## 关键能力
- **扫码**: `uni.scanCode` 兼容一维码 / 二维码
- **离线缓存**: 单据草稿缓存到本地, 联网后批量提交
- **推送**: 库存预警通过 uniPush 推送
- **微信生态**: 公众号 OAuth 登录, 小程序直接运行
- **GPS 定位**: 外勤盘点时记录经纬度 (可在后端扩展)

## 默认账号
admin / admin123
