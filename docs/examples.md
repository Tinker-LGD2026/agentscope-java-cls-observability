# 示例说明

## 离线最小 Demo

入口：

`demo/src/main/java/io/github/tinkerlgd2026/agentscope/cls/demo/DemoApplication.java`

特点：

- 不需要模型密钥；
- 不需要腾讯云凭据；
- 使用确定性本地 Model；
- Console 输出 CLS JSON；
- 覆盖 Entry、Agent、Step、Chat。

运行：

```bash
./mvnw install -DskipTests
./mvnw -f demo/pom.xml exec:java
```

适合第一次验证 SDK 是否成功挂载。

## 真实多 Agent 旅行 Demo

入口：

`demo/src/main/java/io/github/tinkerlgd2026/agentscope/cls/demo/travel/TravelPlannerApplication.java`

真实依赖：

- DeepSeek `deepseek-chat`；
- Open-Meteo Geocoding；
- Open-Meteo Forecast；
- 腾讯云 CLS。

拓扑：

```text
travel-planner
├── ask_weather_expert
│   └── weather-expert
│       └── query_weather
├── ask_itinerary_expert
│   └── itinerary-expert
└── calculate_budget
```

运行：

```bash
export DEEPSEEK_API_KEY='<model-key>'
export CLS_TRANSPORT=cloud
export CLS_ENDPOINT='ap-shanghai.cls.tencentcs.com'
export CLS_TOPIC_ID='<trace-topic-id>'
export CLS_SECRET_ID='<secret-id>'
export CLS_SECRET_KEY='<secret-key>'
export CLS_CONTENT_CAPTURE=off
export TRAVEL_SESSION_ID='travel-session-001'
export TRAVEL_USER_ID='customer-001'

./mvnw -f demo/pom.xml \
  -Dexec.mainClass=io.github.tinkerlgd2026.agentscope.cls.demo.travel.TravelPlannerApplication \
  exec:java
```

该示例会产生模型费用和公网请求。Open-Meteo 不需要 API Key。

## 示例不是生产应用模板

Demo 用于解释接入和验证拓扑。生产应用仍需自行实现：

- 登录与租户鉴权；
- Session 数据库存储；
- Agent 实例并发隔离；
- HTTP API；
- 优雅停止；
- 指标和告警；
- Secret 管理；
- 数据合规和保留策略。
