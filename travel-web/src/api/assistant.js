import request from '@/utils/request'

/**
 * 旅游助手对话（后端 Spring AI，耗时较长）
 */
export const assistantChat = (data) =>
  request.post('/assistant/chat', data, { timeout: 120000 })
