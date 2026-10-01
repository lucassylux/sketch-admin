<template>
  <!-- 品牌占位徽标：方角渐变 + 首字母。接入项目时替换为自己的 Logo 组件即可 -->
  <span class="brand-logo" :style="{ width: size + 'px', height: size + 'px', fontSize: size * 0.42 + 'px' }" aria-hidden="true">
    {{ APP_INITIAL }}
  </span>
</template>

<script setup lang="ts">
// APP_NAME 是脚手架唯一的品牌占位符：init 脚本全局替换
const APP_NAME = '{{APP_NAME}}'
const APP_INITIAL = APP_NAME.replace(/\{\{|\}\}/g, '').charAt(0).toUpperCase()
withDefaults(defineProps<{ size?: number }>(), { size: 28 })
</script>

<style scoped>
.brand-logo {
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 8px; color: #fff; font-weight: 700; flex: none;
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 55%, #a855f7 100%);
}
</style>
