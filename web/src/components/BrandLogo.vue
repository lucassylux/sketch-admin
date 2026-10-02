<template>
  <!-- 运行时品牌：系统设置→品牌信息配置的 SVG 优先；未配置按应用名首字生成 lettermark（通用兜底） -->
  <span class="brand-logo" :style="{ width: size + 'px', height: size + 'px' }" v-html="logoHtml"></span>
</template>

<script setup lang="ts">
// APP_NAME 是脚手架唯一的品牌占位符：init 脚本全局替换
import { computed, onMounted } from 'vue'
import { lettermarkSvg, loadBrand, useBrand } from '../utils/brandMark'

const APP_NAME = '{{APP_NAME}}'
const brand = useBrand()
withDefaults(defineProps<{ size?: number }>(), { size: 28 })

const logoHtml = computed(() =>
  brand.value.logoSvg || brand.value.iconSvg || lettermarkSvg(brand.value.appName, APP_NAME))

onMounted(() => { void loadBrand() })
</script>

<style scoped>
.brand-logo { display: inline-flex; align-items: center; justify-content: center; flex: none; }
.brand-logo :deep(svg) { width: 100%; height: 100%; display: block; }
</style>
