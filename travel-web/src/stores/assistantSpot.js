import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 景点详情加载后写入，供布局中的旅游助手使用（地区等上下文） */
export const useAssistantSpotStore = defineStore('assistantSpot', () => {
  const spotId = ref(undefined)
  const province = ref('')

  function setFromSpot(id, regionName) {
    spotId.value = id
    province.value = regionName || ''
  }

  function clear() {
    spotId.value = undefined
    province.value = ''
  }

  return { spotId, province, setFromSpot, clear }
})
