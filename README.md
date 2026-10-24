# RiverFinderGUI

A GUI tool for searching large river areas in Minecraft Java Edition 1.18+.

一个用于搜索 Minecraft Java 版 1.18+ 大面积河流区域的 GUI 工具。

## How to Run / 如何运行

**Java 17 or higher is required.**

**此程序需要 Java 17 或更高版本。**

Right-click the `.jar` file, choose **Open with**, and select **Java 17+** to launch the program.

右键 `.jar` 文件，选择**打开方式**，并使用 **Java 17 及以上版本**启动程序。

## How to Use / 如何使用

The program has two tabs: **Single Seed Search** and **Search from Seed List**.

程序有两个标签页：**单种子搜索**和**从种子列表搜索**。

### Single Seed Search / 单种子搜索

- Enter seed, thread count, minimum river area, minimum river biome ratio, and search range (`MinX/MaxX/MinZ/MaxZ`).
- The **Version** dropdown currently offers one generation base, **1.18~26.3+**, which uses the 26.3 cubiomes biome tables.
- Click **Start Search** to begin. You can **Pause**, **Resume**, or **Stop**.
- Results show coordinate, area, and ratio. You can **Sort** and **Export**.
- Use **Reset Search Area to Default** to restore default range.

- 输入种子、线程数、最低河流面积、最低河流群系占比和搜索范围（`MinX/MaxX/MinZ/MaxZ`）。
- **版本**下拉框当前只有一个选项 **1.18~26.3+**，使用 26.3 的 cubiomes 群系表进行计算。
- 点击**开始搜索**开始任务，可在过程中**暂停**、**继续**或**停止**。
- 结果显示坐标、面积、占比，可进行**排序**和**导出**。
- 使用**重置搜索区域为默认值**恢复默认范围。

### Search from Seed List / 从种子列表搜索

- Click **Select File** and choose a text file with one seed per line.
- Configure parameters, then click **Start Search** for batch search.
- After completion, you can export full results or export only seed list.
- Supports sorting by area or distance.

- 点击**选择文件**，选择每行一个种子的文本文件。
- 设置参数后点击**开始搜索**进行批量搜索。
- 完成后可导出完整结果或仅导出种子列表。
- 支持按面积或按距离排序。

## Features / 功能特点

- Multi-threaded river search
- Pause, resume, and stop controls
- Real-time progress and time estimation
- Batch search from seed list files
- Result sorting and export support

- 多线程河流搜索
- 支持暂停、继续和停止
- 实时进度与时间估算
- 支持从种子列表批量搜索
- 支持结果排序与导出

## Core Libraries / 核心依赖

- [RiverFinder (melationin)](https://github.com/melationin/riverfinder) - Base river searching library/reference implementation
- [cubiomes (xpple fork)](https://github.com/xpple/cubiomes) - Biome and terrain generation support (26.3)

- [RiverFinder (melationin)](https://github.com/melationin/riverfinder) - 基础河流查找库/参考实现
- [cubiomes (xpple fork)](https://github.com/xpple/cubiomes) - 群系与地形生成支持（26.3）

## Notes / 注意事项

- Use a thread count that matches your CPU capability for best stability.
- Native libraries (`dll`/`so`) are packaged in the jar and extracted to a temporary directory at runtime.
- 建议根据 CPU 性能设置线程数以获得更稳定的体验。
- 原生库（`dll`/`so`）已打包进 jar，运行时会自动解压到临时目录加载。

---

**Find better rivers, faster.**
**更快地找到更好的河流。**
