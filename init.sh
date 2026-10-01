#!/usr/bin/env bash
# sketch-admin 脚手架：从模板生成新项目（替换品牌占位符 + 拷贝所选后端骨架）
# 用法: ./init.sh <app-name> <应用名> <副标题> <目标目录> <go|java>
set -euo pipefail

APP_NAME="${1:?用法: ./init.sh <app-name> <应用名> <副标题> <目标目录> <go|java>}"
TITLE="${2:?缺应用名}"
TAGLINE="${3:?缺副标题}"
DEST="${4:?缺目标目录}"
BACKEND="${5:?后端选 go 或 java}"

ROOT="$(cd "$(dirname "$0")" && pwd)"

# 1) 前端模板拷贝 + 品牌替换
mkdir -p "$DEST"
cp -r "$ROOT/web" "$DEST/web"
rm -rf "$DEST/web/node_modules" "$DEST/web/dist"
python3 - "$DEST" "$APP_NAME" "$TITLE" "$TAGLINE" <<'PY'
import sys, pathlib, json, re

dest, app_name, title, tagline = sys.argv[1:5]
web = pathlib.Path(dest) / "web"

# 文本占位符全局替换（排除 node_modules 已删；dist 已删）
for p in web.rglob('*'):
    if p.is_file() and p.suffix in {'.vue', '.ts', '.html', '.js', '.json', '.md'}:
        s = p.read_text(encoding='utf-8')
        s2 = (s.replace('{{APP_NAME}}', title)
                .replace('{{APP_TAGLINE}}', tagline)
                .replace('{{app-name}}', app_name))
        if s2 != s:
            p.write_text(s2, encoding='utf-8')

pkg = web / 'package.json'
d = json.loads(pkg.read_text(encoding='utf-8'))
d['name'] = f'{app_name}-web'
d['version'] = '0.1.0'
pkg.write_text(json.dumps(d, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('web branded')
PY

# 2) 后端骨架
case "$BACKEND" in
  go)
    mkdir -p "$DEST/server"
    cat > "$DEST/server/go.mod" <<EOF
module example.com/$APP_NAME/server

go 1.24

require github.com/xzsoft/sketch-admin/go-admin v0.0.0
EOF
    cat > "$DEST/server/main.go" <<'EOF'
package main

import (
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"

	admin "github.com/xzsoft/sketch-admin/go-admin"
)

func main() {
	db := getenv("ADMIN_DB", "admin.db")
	store, generated, err := admin.Open(db, os.Getenv("ADMIN_SEED_PASSWORD"))
	if err != nil {
		log.Fatal(err)
	}
	defer store.Close()
	if generated != "" {
		fmt.Printf("=== 首启：管理员账号 admin，初始口令（仅此一次显示）: %s ===\n", generated)
	}
	// 前端产物内嵌（构建后放 ./web-dist）；无 UI 传 nil 纯 API
	var ui fs.FS
	srv := admin.NewServer(store, ui)
	// TODO: 在此挂业务端点：mux := srv.Handler() 后包一层加自己的路由
	log.Println("listening :8280")
	log.Fatal(http.ListenAndServe(":8280", srv.Handler()))
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
EOF
    echo "go 骨架就绪：cd $DEST/server && go mod tidy（replace 指向本仓库 go-admin 或发版后用版本号）"
    ;;
  java)
    mkdir -p "$DEST/server"
    cat > "$DEST/server/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>$APP_NAME-server</artifactId>
  <version>0.1.0</version>
  <properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>com.xzsoft</groupId>
      <artifactId>sketch-admin-spring-boot-starter</artifactId>
      <version>0.1.0</version>
    </dependency>
  </dependencies>
</project>
EOF
    mkdir -p "$DEST/server/src/main/resources" "$DEST/server/src/main/java"
    cat > "$DEST/server/src/main/resources/application.yml" <<EOF
server:
  port: 8280
sketch-admin:
  db-path: data/admin
  # seed-admin-password: 留空则随机生成并日志打印一次
EOF
    echo "java 骨架就绪：cd $DEST/server（先 mvn install 本仓库 starter，或发布到私服后引用）"
    ;;
  *)
    echo "后端只支持 go|java" >&2; exit 1;;
esac

echo
echo "✅ 项目已生成：$DEST"
echo "   前端：cd $DEST/web && npm install && npm run dev"
echo "   品牌：改 src/brand.ts 与 components/BrandLogo.vue"
