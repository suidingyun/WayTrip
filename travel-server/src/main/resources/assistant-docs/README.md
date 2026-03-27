# 本地 RAG 语料

将旅游攻略 Markdown 放入本目录（`*.md`），启动服务时会自动切块并写入向量库。

**主语料**：`travel_guide_corpus.md` 由项目根目录 `travel_guide.xlsx` 生成（804 条目的地，块之间用 `---` 分隔，便于分块）。更新 Excel 后请在仓库根目录执行：

`python scripts/convert_travel_guide_xlsx_to_md.py`（需 `pip install openpyxl`）

也可用 Excel/数据库自行导出为 `.md`（可按「目的地」分多文件；后续可在 front-matter 中标注省份做元数据过滤）。

---

## 示例片段

### 示例目的地

**交通**：可乘高铁至临近车站后换乘景区直通车。  
**美食**：当地米粉、酸菜鱼。  
**提示**：雨天路滑，注意台阶。
