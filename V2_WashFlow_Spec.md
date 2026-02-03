1) APP 状态机 × PLC 状态位对照表（V2）
核心变化：
•	写 MODE 即“启动请求”（脉冲，PLC 自动清零）
•	洗车中再次写 MODE 会被 PLC 忽略
•	M60/M71 允许 APP 发起
•	GateCheck 的“硬门禁”以 217/240 为主；752 保留展示与策略化等待，但不再作为唯一硬阻断依据（原因：厂商口径 + 你现场曾有冲突语义，V2 明确“以 240 为准”）
PLC 位/寄存器角色（V2）
•	只读状态：217 / 240 / 214 / 102 / 752（只读）
•	写启动请求（Mode）：M260 / M261 / M262 / M264（写 1 脉冲）
•	控制：M60（强制终止），M71（暂停/继续）
________________________________________
状态机对照表（可直接指导代码实现）
WashFlowState（V2）	UI允许操作	关键PLC条件（读）	APP动作（写/读）	下一步迁移
Idle	浏览/进入选择	仅需轮询快照；若217=1显示故障但仍可浏览（按你要求：选套餐前也要能“走下去”？你V1里是“故障阻断”，V2保持“故障阻断真实流程”，但允许浏览）	首页轮询 217/240/214/102/752，更新 CarWashSnapshot	点击开始 → Selecting
Selecting	选择MODEL1–4、附加服务	只要217=0即可继续到支付（不要求240/752/车到位）	读217（低频即可）	确认 → PaymentSelecting
PaymentSelecting	选择支付方式	只要217=0即可进入真实支付（仍不要求240/752）	读217（低频）	选卡 → Paying_Card；选现金 → Paying_Cash
Paying_Card	POS真实支付	仅要求217=0（过程中若217变1 -> 终止并失败/人工策略）	调POS支付；可并行读217	Approved → PaymentAuthorized；否则 PaymentFailed→PaymentSelecting
Paying_Cash	现金收款+找零策略	仅要求217=0（同上）	现金逻辑（库存/可找零性判定/拒收提示/找零执行）	成功→PaymentAuthorized；失败→Manual/Failed
PaymentAuthorized	显示“已支付”	无	固化 PaymentResult(txId…)	进入 GateCheck（下一状态）
GateCheck_Fault217	提示故障阻断	217=1	只读，不复位	217=0 → 进入 GateCheck_Wait240（或继续检查）
GateCheck_Wait240	等待就绪（前车/车位/安全条件由PLC内部处理）	240=0	轮询240（策略化超时）	240=1 → Start_SendingMode
GateCheck_Wait752（可选/策略化）	仅当你们现场确认752语义且需要时启用	752=1（含义依部署确认）	轮询752（策略化）	满足条件→进入Wait240或直接Start
Start_SendingMode	发送启动请求	必须：217=0 且240=1	写MODE寄存器=1（脉冲）	写完→Start_Wait214
Start_Wait214	等待PLC进入运行态	214=0→等待	轮询214（策略化）	214=1 → Running_Wait102End
Running_Wait102End	运行监控	214=1 或 102=运行态	轮询102/214（策略化，支持auto-extend）；允许M71暂停/继续；允许M60取消	结束→Completed；取消→TimeoutHandling/Refunding
Completed	完成提示/回首页	结束态（建议：214=0 且/或102结束）	清理交易状态	→ Idle
TimeoutHandling(phase, soft	hard)	提示/延长/转退款	取决于phase	执行策略动作
Refunding	退款中	-	卡：void/refund；现金：退回/找零	→ Refunded 或 ManualInterventionRequired
Refunded	退款完成	-	清理状态	→ Idle
ManualInterventionRequired	人工介入页	-	展示原因/已付金额/指引	人工处理后 → Idle
Failed	失败页	-	多语言错误提示	→ Idle/PaymentSelecting
________________________________________
2) V1 → V2 变更摘要（你快速核对用）
✅ 已同步厂商确认的“新事实”
1.	启动指令变更：不再“写 M262=1 启动”，改为 “写 MODE 寄存器即启动请求”
2.	MODE 写入特性：脉冲，自动清零（APP 不回写0）
3.	洗车中再次写 MODE：PLC 自动忽略（APP也要防呆禁止重发）
4.	M60 / M71：允许 APP 发起；M60 强制终止
5.	M60 后状态（厂商口径）：
o	强制终止后 752=0，不会变1（厂商：752只有完整完成洗车才会变1）
o	M240 需要车开走才会变为允许（1）
✅ 已同步你新增的业务硬规则
•	选套餐/支付阶段：只要 217 != 1（无故障） 就应该能继续
→ 不再用 240/752 阻断支付入口
→ 240/752 只用于“支付后 GateCheck + 启动前门禁/等待”
🔧 架构性变化
•	Start_SendingM262 → Start_SendingMode
•	等待点保留策略化，但 WAIT_752 在 V2 中变为 可选策略点（默认以 WAIT_240 为主），原因是 752 语义出现“厂商口径 vs 现场旧结论”的冲突风险，V2 明确：以 240 为硬门禁。
________________________________________
3) 洗车机 APP 真实运行逻辑 / 支付流程
基准 V2（完整版，唯一执行标准）
V2 的定位：在完全继承你 V1 的“状态机驱动/策略化等待/双语约束/首页轮询”等硬规则基础上，用厂商最终确认的 PLC 行为替换掉 V1 中已过时的启动方式。
________________________________________
一、全局硬规则（V2 不可违反）
1) 状态机驱动
•	全流程由 WashFlowState 驱动
•	UI 只能根据 State 渲染，不直接“猜测设备状态”
•	任何设备事件（POS/现金/PLC）只能通过事件/用例推动状态迁移
2) 支付 ≠ 启动（强制 GateCheck）
•	支付成功后必须 GateCheck 通过才允许“发起启动请求”
•	V2 GateCheck 的硬门禁为：
o	217=0（无故障）
o	240=1（PLC Ready/允许启动）
•	752 保留为可选等待点（WAIT_752），仅在你们现场最终确认其语义后启用；默认不作为唯一硬阻断依据
仍然遵守你的原则：不允许出现“收钱但无法启动”的路径
V2 的实现手段是：支付后进入策略化等待（WAIT_240/WAIT_214…），并在 hard timeout 时进入 RefundOrManual。
3) 等待必须策略化（禁止写死分钟数）
•	等待点：WAIT_752 / WAIT_240 / WAIT_214 / WAIT_102
•	每个等待点必须：按 MODEL1–4 可配置 soft/hard 超时与动作
•	流程代码中禁止出现固定 5/3/1 分钟
4) 双语强约束（English / Deutsch）
•	APP 默认两种语言：en / de
•	首页选择语言 = 全局语言
•	所有界面 + 所有对话框 + 所有错误提示 + 所有按钮文案都必须跟随该语言
•	禁止出现中文；禁止 EN/DE 混屏
•	所有文本必须来自统一文案系统（Android string resources 或统一 UiText）
________________________________________
二、语言系统基准（架构必须实现）
1.	首页选择语言 → AppLanguage = EN | DE
2.	语言必须持久化（DataStore/SharedPreferences）
3.	UI 只能通过 UiText 获取文案（含 Dialog/Toast/Error）
________________________________________
三、首页：设备状态展示 + 洗车机状态轮询（V2 必须包含）
目标：解决“洗车机状态变化后首页不更新”的问题。
规则（必须实现）
1.	进入首页立即刷新一次关键状态（至少：217/752/240/214/102）
2.	首页可见期间持续轮询（可固定间隔）
3.	每次轮询更新 CarWashSnapshot 并携带 timestamp
4.	首页展示基于最新快照：
o	快照过期（超过 snapshotMaxAge）→ 显示“状态未知 / 正在刷新”
o	禁止长期显示旧故障
5.	进入支付/运行流程时：
o	首页轮询可降频或暂停（避免抢串口/冲突）
o	流程内使用专用轮询（GateCheck/Running 属于流程一部分）
________________________________________
四、设备准备阶段（Device Ready）
设备：
•	NOTE（纸币器）
•	COIN（硬币器）
•	POS（刷卡）
•	PLC（洗车机）
规则：
•	NOTE/COIN 独立：任意一个可用 → 允许现金入口（并提示另一设备不可用）
•	POS 可用 → 允许卡入口
•	PLC 不在线 → 禁止进入真实支付（只能浏览/选择）
________________________________________
五、选择商品阶段（Select Program）
用户选择：
•	MODEL1–MODEL4
•	附加服务（如有）
生成：
•	OrderDraft(model, options, amount, currency, language)
V2 新增硬规则（同步你昨天要求）
•	只要 217=0（无故障），用户应当可以完成选择并进入支付
•	不要求 240=1，不要求 752，不要求车到位
________________________________________
六、支付阶段（Start Payment）
所有支付方式必须产出统一结果：
•	PaymentResult(txId, amount, method, meta)
A) 卡支付（POS）
1.	创建 txId
2.	调用真实 POS 支付
3.	返回：
•	APPROVED → PaymentAuthorized
•	DECLINED/CANCELLED/ERROR → PaymentFailed(reason) 回支付选择（多语言提示）
4.	打印小票可做，但打印失败不得改变交易结果
V2 门禁同步
•	支付阶段仅要求 217=0
•	若支付中 217 变 1 → 按策略中止并进入 Failed/Manual（以你们现场安全要求为准）
________________________________________
B) 现金支付（NOTE / COIN）——V2 继承 V1 明确细化版（不删减）
（此段保持你 V1 的现金找零全套硬规则不变：库存判定、可找零性、拒收提示、找零失败不允许假成功、取消分情况等。）
V2 只补充：支付阶段仍仅要求 217=0；240/752 不应阻断用户投入与找零逻辑。
________________________________________
七、GateCheck（启动前闸门检查）——V2 修订版
寄存器（只读）：
•	217 故障（217=1 阻断）
•	240 就绪（240=0 等待；240=1 才允许发起启动请求）
•	752：可选等待点（若启用，语义以现场最终确认的版本为准）
状态：
•	GateCheck_Fault217
•	GateCheck_Wait240
•	GateCheck_Wait752（可选）
V2 核心原则
•	GateCheck 发生在 PaymentAuthorized 之后
•	GateCheck 不通过 → 进入策略化等待（soft/hard timeout → refund/manual）
•	绝不出现“收钱后直接写启动”而不检查 217/240 的路径
________________________________________
八、等待点超时策略（必须写入流程）——V2 保持不变
WaitPhase：
•	WAIT_752 / WAIT_240 / WAIT_214 / WAIT_102
每个 phase 按 MODEL1–4 配置：
•	softTimeoutSec：到点只提示/通知/延长，不退款
•	hardTimeoutSec：到点进入 RefundOrManual（或禁止自动退款）
•	pollIntervalMs
•	actions（UI提示、蜂鸣、通知运维、auto-extend）
特别强调：
•	WAIT_102（运行中）受车长影响最大：
o	soft：只提示/通知/延长
o	hard：默认更倾向 ManualInterventionRequired，避免误退款
________________________________________
九、启动（Start Wash）——V2 重大更新
V1：写 M262=1
V2：写 MODE 寄存器 = 1（脉冲）即发起启动请求
流程：
1.	GateCheck 通过（217=0 & 240=1；如启用 WAIT_752 则需其满足）
2.	写入对应 MODE 寄存器（一次脉冲即可，PLC 自动清零）：
o	Mode1 → M261=1
o	Mode2 → M262=1
o	Mode3 → M260=1
o	Mode4 → M264=1
3.	等待 214 进入运行态（WAIT_214）
4.	进入运行监控（WAIT_102）
厂商确认同步（V2 硬规则）
•	MODE 写入：脉冲，自动清零（APP 不回写 0）
•	洗车中再次写 MODE：PLC 自动忽略（APP 也必须防呆禁止重发）
________________________________________
十、运行监控（Running）——V2 保持 + 补充控制
•	轮询 102（以及可结合 214）直到结束
•	走 WAIT_102 策略化超时（支持 auto-extend）
•	允许用户操作：
o	暂停/继续：写 M71（1/0）
o	取消：写 M60=1（强制终止）
________________________________________
十一、完成（Finish）
•	102 结束 → Completed
•	显示完成页（全多语言）
•	清理交易状态 → 回首页
________________________________________
十二、异常/退款/人工介入（统一入口）——V2 保持
触发：hard timeout / 不可恢复错误 / 取消 / 找零失败等
•	卡：POS void/refund（按 SDK 能力）
•	现金：自动退回；失败 → 人工介入
•	UI 必须明确显示：原因、已付金额、下一步（全多语言）
V2 同步厂商对取消后的状态
•	M60 强制终止后：
o	752=0 且不会变 1（厂商口径）
o	M240 需车子开走才变为允许（1）
________________________________________
十三、基准状态枚举（V2 版，含义不得乱改）
在你 V1 枚举基础上，做“最小必要改动”：仅替换掉与启动方式相关的状态名。
•	Idle
•	Selecting
•	PaymentSelecting
•	Paying_Card
•	Paying_Cash
•	PaymentAuthorized
•	GateCheck_Wait752（可选启用）
•	GateCheck_Wait240
•	GateCheck_Fault217
•	Start_SendingMode（替代 Start_SendingM262）
•	Start_Wait214
•	Running_Wait102End
•	Completed
•	TimeoutHandling(phase, soft|hard)
•	Refunding
•	Refunded
•	ManualInterventionRequired
•	Failed
________________________________________

