<template>
  <div class="page-fill">
    <SkCard title="用户管理" class="fill-card">
      <span class="sk-space toolbar" style="margin: 0 0 12px">
        <SkButton variant="primary" :disabled="me?.role !== 'admin'" @click="openEdit(null)">新建用户</SkButton>
      </span>
      <SkTable :columns="columns" :data="rows" :loading="loading" row-key="username" size="md">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'role'">
            <SkTag :color="roleColor[record.role] || 'default'">{{ roleLabel[record.role] || record.role }}</SkTag>
          </template>
          <template v-else-if="column.key === 'source'">
            <SkTag :color="record.source === 'oidc' ? 'info' : 'default'">{{ record.source === 'oidc' ? 'SSO' : '本地' }}</SkTag>
          </template>
          <template v-else-if="column.key === 'createdAt'">
            {{ fmtTime(record.createdAt) }}
          </template>
          <template v-else-if="column.key === 'lastLoginAt'">
            {{ fmtTime(record.lastLoginAt) }}
          </template>
          <template v-else-if="column.key === 'ops'">
            <SkButton size="sm" :disabled="record.username === me?.username || me?.role !== 'admin'" @click="openEdit(record)">编辑</SkButton>
            <SkPopconfirm title="确认删除该用户？" @confirm="removeUser(record.username)">
              <SkButton size="sm" variant="danger" style="margin-left: 6px" :disabled="record.username === me?.username">删除</SkButton>
            </SkPopconfirm>
          </template>
        </template>
      </SkTable>
    </SkCard>

    <!-- 编辑弹窗：新建（用户名+密码+角色）或编辑（仅角色） -->
    <SkModal v-model:open="editOpen" :title="form._exists ? `编辑用户：${form.username}` : '新建用户'" width="480px">
      <SkForm ref="formRef" label-width="92px">
        <SkFormField v-if="!form._exists" name="username" label="用户名" required :rules="usernameRules">
          <SkInput v-model="form.username" placeholder="如 alice" />
        </SkFormField>
        <SkFormField v-if="!form._exists" name="password" label="初始密码" required :rules="passwordRules">
          <SkInput v-model="form.password" type="password" autocomplete="new-password" placeholder="至少 8 位" />
        </SkFormField>
        <SkFormField name="role" label="角色" required>
          <SkSelect v-model="form.role" :options="roleOptions" />
        </SkFormField>
      </SkForm>
      <template #footer>
        <SkButton @click="editOpen = false">取消</SkButton>
        <SkButton variant="primary" :loading="saving" style="margin-left: 8px" @click="save">保存</SkButton>
      </template>
    </SkModal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { skMessage } from '@xzsoft/sketch-ui'
import { api, type Me } from '../api'
import type { SkFormInstance, SkRule } from '../types/form'

interface UserRow {
  username: string
  role: string
  source: string
  createdAt: string
  lastLoginAt?: string
}

const me = ref<Me | null>(null)
const rows = ref<UserRow[]>([])
const loading = ref(false)
const editOpen = ref(false)
const saving = ref(false)
const formRef = ref<SkFormInstance | null>(null)

const roleColor: Record<string, string> = { admin: 'danger', editor: 'warning', viewer: 'default' }
const roleLabel: Record<string, string> = { admin: '管理员', editor: '编辑', viewer: '只读' }
const roleOptions = [
  { label: '管理员', value: 'admin' },
  { label: '编辑', value: 'editor' },
  { label: '只读', value: 'viewer' },
]
const usernameRules: SkRule[] = [
  { required: true, message: '用户名必填' },
  { pattern: /^[a-zA-Z0-9][a-zA-Z0-9_-]{1,31}$/, message: '2-32 位字母数字与 _ -' },
]
const passwordRules: SkRule[] = [
  { required: true, message: '初始密码必填' },
  { min: 8, message: '至少 8 位' },
]

const columns = [
  { title: '用户名', key: 'username', width: 160 },
  { title: '角色', key: 'role', width: 100, align: 'center' },
  { title: '来源', key: 'source', width: 90, align: 'center' },
  { title: '创建时间', key: 'createdAt', width: 160, align: 'center' },
  { title: '最近登录', key: 'lastLoginAt', width: 160, align: 'center' },
  { title: '操作', key: 'ops', width: 150, align: 'center' },
]

const fmtTime = (s?: string) => (s ? s.replace('T', ' ').slice(0, 16) : '—')

const load = async () => {
  loading.value = true
  try {
    me.value = await api.get<Me>('/api/auth/me')
    if (me.value.role === 'admin') {
      rows.value = await api.get<UserRow[]>('/api/users')
    } else {
      rows.value = []
      skMessage.warning('仅管理员可查看用户列表')
    }
  } catch (e) {
    skMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const form = reactive<{ username: string; password: string; role: string; _exists: boolean }>({
  username: '', password: '', role: 'viewer', _exists: false,
})

const openEdit = (u: UserRow | null) => {
  Object.assign(form, u || { username: '', password: '', role: 'viewer' }, { _exists: !!u })
  editOpen.value = true
}

const save = async () => {
  try { await formRef.value?.validate() } catch { return }
  if (saving.value) return
  saving.value = true
  try {
    if (form._exists) {
      await api.put(`/api/users/${form.username}/role`, { role: form.role })
    } else {
      await api.post('/api/users', { username: form.username, password: form.password, role: form.role })
    }
    skMessage.success('已保存')
    editOpen.value = false
    load()
  } catch (e) {
    skMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

const removeUser = async (username: string) => {
  try {
    await api.del(`/api/users/${username}`)
    skMessage.success('已删除')
    load()
  } catch (e) {
    skMessage.error((e as Error).message)
  }
}
</script>
