/**
 * scheduler 模块 —— 契约 §2.3(24-25)
 * 24 POST /changes/{changeNo}/schedule   25 DELETE /changes/{changeNo}/schedule
 */
import { api } from '../http'
import type { ScheduleJob, ScheduleRequest } from '../types'

/** 24. 创建定时发布任务（PUBLISH；错误 SCH-409-001） */
export function createSchedule(changeNo: string, data: ScheduleRequest) {
  return api.post<ScheduleJob>(`/changes/${changeNo}/schedule`, data)
}

/** 25. 取消定时发布任务（PUBLISH；幂等；错误 SCH-409-001） */
export function cancelSchedule(changeNo: string) {
  return api.delete<void>(`/changes/${changeNo}/schedule`)
}
