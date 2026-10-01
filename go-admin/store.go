// Package admin 是 sketch-admin 的 Go 后端实现：登录会话、RBAC、数据字典、
// 审计、系统设置（会话时长/SSO）与用户管理——与 contracts/api.md 契约一一对应。
// 存储 SQLite 单文件（纯 Go 驱动，CGO_ENABLED=0 可交叉编译），零外部依赖。
package admin

import (
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	_ "modernc.org/sqlite" // 纯 Go 驱动：保住 CGO_ENABLED=0 的交叉编译

	"golang.org/x/crypto/bcrypt"
)

// User 登录用户（SSO 用户按 OIDC subject 绑定，防 IdP 同名接管本地账号）
type User struct {
	Username     string `json:"username"`
	PasswordHash string `json:"-"`
	Role         string `json:"role"` // admin / editor / viewer
	Subject      string `json:"-"`
	LastLoginAt  string `json:"-"`
}

// DictItem 字典项（type 分组；label 展示中文、value 编码落库）
type DictItem struct {
	ID      int64  `json:"id,omitempty"`
	Type    string `json:"type"`
	Label   string `json:"label"`
	Value   string `json:"value"`
	Sort    int    `json:"sort"`
	Enabled bool   `json:"enabled"`
}

// DictType 字典类型汇总
type DictType struct {
	Type  string `json:"type"`
	Name  string `json:"name"`
	Count int    `json:"count"`
}

// AuditEntry 审计记录
type AuditEntry struct {
	ID     int64  `json:"id"`
	At     string `json:"at"`
	Actor  string `json:"actor"`
	Action string `json:"action"`
	Entity string `json:"entity"`
	Detail string `json:"detail,omitempty"`
}

var (
	ErrNotFound  = errors.New("not found")
	ErrConflict  = errors.New("conflict")
	ErrForbidden = errors.New("forbidden")
)

// dictTypeNames 内置字典类型的展示名（未知类型回退编码本身）
var dictTypeNames = map[string]string{
	"rule-severity": "规则严重级",
}

// Store SQLite 存储
type Store struct{ db *sql.DB }

// Open 打开（或初始化）库；首启种子 admin（随机口令一次性返回）与内置字典
func Open(path, seedAdminPassword string) (*Store, string, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, "", err
	}
	db.SetMaxOpenConns(1) // 单写者串行：管理后台流量小，彻底规避 SQLITE_BUSY
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		return nil, "", err
	}
	s.seedOIDCEnv()
	if err := s.seedDicts(); err != nil {
		return nil, "", err
	}
	if seedAdminPassword != "" {
		if err := s.SeedAdmin(seedAdminPassword); err != nil {
			return nil, "", err
		}
		return s, "", nil
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n); err != nil {
		return nil, "", err
	}
	if n == 0 {
		pw, err := randomToken(16)
		if err != nil {
			return nil, "", err
		}
		if err := s.SeedAdmin(pw); err != nil {
			return nil, "", err
		}
		return s, pw, nil
	}
	return s, "", nil
}

// Close 关闭库
func (s *Store) Close() error { return s.db.Close() }

func (s *Store) migrate() error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS users(
			username TEXT PRIMARY KEY, password_hash TEXT NOT NULL, role TEXT NOT NULL,
			created_at TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS dicts(
			id INTEGER PRIMARY KEY AUTOINCREMENT, dict_type TEXT NOT NULL,
			label TEXT NOT NULL, value TEXT NOT NULL, sort INTEGER NOT NULL DEFAULT 0,
			enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL)`,
		`CREATE TABLE IF NOT EXISTS audit(
			id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, actor TEXT NOT NULL,
			action TEXT NOT NULL, entity TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')`,
		`CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)`,
	}
	for _, q := range stmts {
		if _, err := s.db.Exec(q); err != nil {
			return fmt.Errorf("初始化表结构: %w", err)
		}
	}
	// 旧库升级（重复列忽略）
	for _, col := range []string{"subject", "last_login_at"} {
		if _, err := s.db.Exec(`ALTER TABLE users ADD COLUMN ` + col + ` TEXT NOT NULL DEFAULT ''`); err != nil &&
			!strings.Contains(err.Error(), "duplicate column") {
			return fmt.Errorf("升级 users 表: %w", err)
		}
	}
	if _, err := s.db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_users_subject ON users(subject) WHERE subject != ''`); err != nil {
		return fmt.Errorf("建 subject 索引: %w", err)
	}
	if _, err := s.db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_dicts_type_value ON dicts(dict_type, value)`); err != nil {
		return fmt.Errorf("建字典唯一索引: %w", err)
	}
	return nil
}

// ---------- 用户 ----------

// SeedAdmin 首启种子管理员（已有用户则跳过）
func (s *Store) SeedAdmin(password string) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n); err != nil || n > 0 {
		return err
	}
	hash, err := bcryptHash(password)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`INSERT INTO users(username, password_hash, role, created_at) VALUES('admin', ?, 'admin', ?)`, hash, now())
	return err
}

// GetUser 按用户名取
func (s *Store) GetUser(username string) (*User, error) {
	u := &User{}
	err := s.db.QueryRow(
		`SELECT username, password_hash, role, subject, last_login_at FROM users WHERE username=?`, username).
		Scan(&u.Username, &u.PasswordHash, &u.Role, &u.Subject, &u.LastLoginAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return u, err
}

// GetUserBySubject 按 OIDC sub 取（SSO 用户唯一绑定键）
func (s *Store) GetUserBySubject(subject string) (*User, error) {
	u := &User{}
	err := s.db.QueryRow(
		`SELECT username, password_hash, role, subject, last_login_at FROM users WHERE subject=?`, subject).
		Scan(&u.Username, &u.PasswordHash, &u.Role, &u.Subject, &u.LastLoginAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return u, err
}

// UserVO 用户列表行（哈希/subject 不出库）
type UserVO struct {
	Username    string `json:"username"`
	Role        string `json:"role"`
	Source      string `json:"source"` // local / oidc
	CreatedAt   string `json:"createdAt"`
	LastLoginAt string `json:"lastLoginAt,omitempty"`
}

// ListUsers 用户列表
func (s *Store) ListUsers() ([]UserVO, error) {
	rows, err := s.db.Query(`SELECT username, role, subject, created_at, last_login_at FROM users ORDER BY username`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []UserVO
	for rows.Next() {
		v := UserVO{}
		var subject string
		if err := rows.Scan(&v.Username, &v.Role, &subject, &v.CreatedAt, &v.LastLoginAt); err != nil {
			return nil, err
		}
		v.Source = map[bool]string{true: "local", false: "oidc"}[subject == ""]
		out = append(out, v)
	}
	return out, rows.Err()
}

// CreateUser 建本地账号（哈希由调用方生成）
func (s *Store) CreateUser(username, passwordHash, role string) error {
	_, err := s.db.Exec(
		`INSERT INTO users(username, password_hash, role, created_at) VALUES(?,?,?,?)`,
		username, passwordHash, role, now())
	return err
}

// UpdateRole 调整角色
func (s *Store) UpdateRole(username, role string) error {
	res, err := s.db.Exec(`UPDATE users SET role=? WHERE username=?`, role, username)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// UpdatePassword 落新密码哈希
func (s *Store) UpdatePassword(username, passwordHash string) error {
	res, err := s.db.Exec(`UPDATE users SET password_hash=? WHERE username=?`, passwordHash, username)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// TouchLogin 记录最近登录时间
func (s *Store) TouchLogin(username string) {
	_, _ = s.db.Exec(`UPDATE users SET last_login_at=? WHERE username=?`, now(), username)
}

// DeleteUser 删除用户
func (s *Store) DeleteUser(username string) error {
	res, err := s.db.Exec(`DELETE FROM users WHERE username=?`, username)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// AdminCount 除指定用户外的现存 admin 数（删除/降级保护用）
func (s *Store) AdminCount(exclude string) int {
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM users WHERE role='admin' AND username!=?`, exclude).Scan(&n)
	return n
}

// UpsertSSOUser SSO 登录落库：按 subject 找到则同步，否则建户（固定 editor）。
// 用户名被本地账号或其他 SSO 账号占用时拒绝
func (s *Store) UpsertSSOUser(username, subject string) (*User, error) {
	if u, err := s.GetUserBySubject(subject); err == nil {
		if u.Role != "editor" {
			_ = s.UpdateRole(u.Username, "editor")
			u.Role = "editor"
		}
		return u, nil
	} else if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	if existing, err := s.GetUser(username); err == nil {
		kind := "本地账号"
		if existing.Subject != "" {
			kind = "其他 SSO 账号"
		}
		return nil, fmt.Errorf("%w: 用户名 %s 已被%s占用", ErrConflict, username, kind)
	} else if !errors.Is(err, ErrNotFound) {
		return nil, err
	}
	random, err := randomToken(24)
	if err != nil {
		return nil, err
	}
	hash, err := bcryptHash(random)
	if err != nil {
		return nil, err
	}
	if _, err := s.db.Exec(
		`INSERT INTO users(username, password_hash, role, subject, created_at) VALUES(?,?,?,?,?)`,
		username, hash, "editor", subject, now()); err != nil {
		return nil, err
	}
	return &User{Username: username, Role: "editor", Subject: subject}, nil
}

// ---------- 字典 ----------

func (s *Store) seedDicts() error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM dicts`).Scan(&n); err != nil || n > 0 {
		return err
	}
	seed := []DictItem{
		{Type: "rule-severity", Label: "严重", Value: "critical", Sort: 1, Enabled: true},
		{Type: "rule-severity", Label: "高", Value: "high", Sort: 2, Enabled: true},
		{Type: "rule-severity", Label: "中", Value: "medium", Sort: 3, Enabled: true},
		{Type: "rule-severity", Label: "低", Value: "low", Sort: 4, Enabled: true},
	}
	for _, d := range seed {
		if err := s.SaveDict(&d, "seed"); err != nil {
			return err
		}
	}
	return nil
}

// ListDictTypes 类型汇总（有项才出现）
func (s *Store) ListDictTypes() ([]DictType, error) {
	rows, err := s.db.Query(`SELECT dict_type, COUNT(*) FROM dicts GROUP BY dict_type ORDER BY dict_type`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DictType
	for rows.Next() {
		t := DictType{}
		if err := rows.Scan(&t.Type, &t.Count); err != nil {
			return nil, err
		}
		t.Name = dictTypeNames[t.Type]
		if t.Name == "" {
			t.Name = t.Type
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// ListDicts 某类型的字典项（排序 sort,id；onlyEnabled 业务取值过滤）
func (s *Store) ListDicts(dictType string, onlyEnabled bool) ([]DictItem, error) {
	sb := strings.Builder{}
	sb.WriteString(`SELECT id,dict_type,label,value,sort,enabled FROM dicts WHERE dict_type=?`)
	if onlyEnabled {
		sb.WriteString(` AND enabled=1`)
	}
	sb.WriteString(` ORDER BY sort,id`)
	rows, err := s.db.Query(sb.String(), dictType)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DictItem
	for rows.Next() {
		d := DictItem{}
		var en int
		if err := rows.Scan(&d.ID, &d.Type, &d.Label, &d.Value, &d.Sort, &en); err != nil {
			return nil, err
		}
		d.Enabled = en == 1
		out = append(out, d)
	}
	return out, rows.Err()
}

// SaveDict 新增或更新（同类型下 value 唯一）
func (s *Store) SaveDict(d *DictItem, actor string) error {
	if d.Type == "" || d.Label == "" || d.Value == "" {
		return errors.New("type/label/value 必填")
	}
	isCreate := d.ID == 0
	if isCreate {
		res, err := s.db.Exec(
			`INSERT INTO dicts(dict_type,label,value,sort,enabled,created_at) VALUES(?,?,?,?,?,?)`,
			d.Type, d.Label, d.Value, d.Sort, b2i(d.Enabled), now())
		if err != nil {
			return fmt.Errorf("同类型下 value 需唯一: %w", err)
		}
		d.ID, _ = res.LastInsertId()
	} else {
		res, err := s.db.Exec(
			`UPDATE dicts SET dict_type=?,label=?,value=?,sort=?,enabled=? WHERE id=?`,
			d.Type, d.Label, d.Value, d.Sort, b2i(d.Enabled), d.ID)
		if err != nil {
			return fmt.Errorf("同类型下 value 需唯一: %w", err)
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
	}
	action := "update"
	if isCreate {
		action = "create"
	}
	_ = s.Audit(actor, action, "dict/"+d.Type+"/"+d.Value, d.Label)
	return nil
}

// DeleteDict 删除字典项
func (s *Store) DeleteDict(id int64) error {
	res, err := s.db.Exec(`DELETE FROM dicts WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ---------- 审计 ----------

// Audit 追加审计（写操作统一收口）
func (s *Store) Audit(actor, action, entity, detail string) error {
	_, err := s.db.Exec(`INSERT INTO audit(at,actor,action,entity,detail) VALUES(?,?,?,?,?)`,
		now(), actor, action, entity, detail)
	return err
}

// ListAudit 审计列表（新→旧）
func (s *Store) ListAudit(limit int) ([]AuditEntry, error) {
	rows, err := s.db.Query(`SELECT id,at,actor,action,entity,detail FROM audit ORDER BY id DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []AuditEntry
	for rows.Next() {
		a := AuditEntry{}
		if err := rows.Scan(&a.ID, &a.At, &a.Actor, &a.Action, &a.Entity, &a.Detail); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// ---------- 设置 ----------

// SettingGet 读设置（不存在返回空串）
func (s *Store) SettingGet(key string) string {
	var v string
	_ = s.db.QueryRow(`SELECT value FROM settings WHERE key=?`, key).Scan(&v)
	return v
}

// SettingSet 写设置（空串即停用该配置项）
func (s *Store) SettingSet(key, value string) {
	_, _ = s.db.Exec(`INSERT INTO settings(key,value) VALUES(?,?)
		ON CONFLICT(key) DO UPDATE SET value=excluded.value`, key, value)
}

// SessionTTLHours 会话时长（1-168；缺省/非法回退 12）
func (s *Store) SessionTTLHours() int {
	var v int
	if _, err := fmt.Sscan(s.SettingGet("session_ttl_hours"), &v); err != nil || v < 1 || v > 168 {
		return 12
	}
	return v
}

// ---------- 助手 ----------

func now() string { return time.Now().Format(time.RFC3339) }

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}

func bcryptHash(pw string) (string, error) {
	b, err := bcrypt.GenerateFromPassword([]byte(pw), bcrypt.DefaultCost)
	return string(b), err
}

func bcryptCompare(hash, pw string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(pw)) == nil
}

const tokenAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

func randomToken(n int) (string, error) {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	out := make([]byte, n)
	for i, b := range buf {
		out[i] = tokenAlphabet[int(b)%len(tokenAlphabet)]
	}
	return string(out), nil
}
