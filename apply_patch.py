#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
移植补丁器（v1.26+ 起为 no-op）。

历史背景：
  v1.24 及以前，移植代码通过 `ttsrv-port-overlay.zip` 在 CI 里解压 + 本脚本打补丁的方式注入。
  v1.25+ 已改为「仓库即真源」（所有移植代码直接提交进仓库），overlay 与补丁都只保留回退用途。

为什么现在必须让它 no-op：
  1) overlay 用 `unzip -n`（只补缺失文件）——一旦某个文件在仓库里被删除，
     它会被 overlay 里的旧版本「复活」，出现「本地一致、CI 编译错」的灵异现象；
  2) 本脚本的补丁是「以特征串为 marker、命中即跳过」的幂等写法——当仓库代码被重构到
     marker 不再存在时，补丁会**重新注入已被删除的旧功能**（例如已拔除的音频二级页入口），
     同样导致 CI 编译失败。

因此：CI 仍会调用本脚本，但它只打印提示，不再改动任何文件。
如确实需要回退到补丁化流程，请从 git 历史里取回当时的 apply_patch.py 与 overlay。
"""
import sys

def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    print('[skip] 仓库即真源（v1.25+）：apply_patch.py 已退役，不再注入任何补丁。')
    print('       workspace =', root)

if __name__ == '__main__':
    main()
