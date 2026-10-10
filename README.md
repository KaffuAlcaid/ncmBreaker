# NCM Breaker

NCM Breaker 可以将本地网易云 NCM 文件批量转换为 MP3 或 FLAC，并为 MP3 写入歌曲信息和专辑封面

**[下载 Windows 版](https://github.com/KaffuAlcaid/ncmBreaker/releases/download/v0.1.0/NcmBreaker-0.1.0-windows-x86_64.zip)** · [下载 Linux 版](https://github.com/KaffuAlcaid/ncmBreaker/releases/download/v0.1.0/NcmBreaker-0.1.0-linux-x86_64.tar.gz) · [全部版本与通用 JAR](https://github.com/KaffuAlcaid/ncmBreaker/releases)

快速导航：[主要功能](#主要功能) · [开始使用](#开始使用) · [转换与输出](#转换与输出) · [文档与反馈](#文档与反馈) · [许可与致谢](#许可与致谢)

## 主要功能

- **批量转换**：添加多个文件或整个文件夹，支持扫描子文件夹和勾选转换
- **歌曲信息与封面**：将 NCM 中的歌曲名、歌手、专辑和封面写入 MP3
- **同名文件处理**：支持自动编号、跳过或覆盖

## 开始使用

Windows x86-64 用户下载并完整解压 ZIP，双击 `NcmBreaker.exe` 即可使用，程序自带 Java 运行环境

1. **添加文件**：点击“添加文件”或“添加文件夹”，勾选需要转换的文件
2. **设置输出**：选择输出目录，按需调整“写入基础标签”“嵌入专辑封面”和同名文件处理方式
3. **开始转换**：点击“开始转换”，完成后在输出目录中查看 MP3 或 FLAC 文件

结果保存在所选输出目录中，默认位置为用户主目录下的 `Music/NCM Output`

<details>
<summary>在 Linux 上使用</summary>

下载并解压 `NcmBreaker-0.1.0-linux-x86_64.tar.gz`，运行解压后的 `NcmBreaker/bin/NcmBreaker`

Linux 版同样自带 Java 运行环境，以 Ubuntu 22.04 x86_64 为构建基线，需要图形桌面以及常见的字体和 X11/XWayland 运行库

</details>

<details>
<summary>使用通用 JAR 与命令行</summary>

安装 Java 17 或更高版本，从 [Releases](https://github.com/KaffuAlcaid/ncmBreaker/releases) 下载 `NcmBreaker-0.1.0.jar`

打开图形界面：

```text
java -jar NcmBreaker-0.1.0.jar
```

检查文件信息：

```text
java -jar NcmBreaker-0.1.0.jar --inspect "音乐目录"
```

将文件夹中的 NCM 批量转换到指定目录：

```text
java -jar NcmBreaker-0.1.0.jar -o "输出目录" "音乐目录"
```

命令行恢复原始音频，MP3 标签和封面写入请使用图形界面。可在命令末尾传入多个文件或目录，省略 `-o` 时结果保存在当前工作目录下的 `output`

</details>

## 转换与输出

当前支持经典 NCM 格式，输出格式与文件中的原始音频一致

| 输出格式 | 歌曲信息与封面 |
| --- | --- |
| MP3 | 可从 NCM 写入歌曲名、歌手、专辑和 JPEG / PNG 封面 |
| FLAC | 保留原始音频中的元数据 |

## 文档与反馈

- [经典 NCM 加密流程](docs/ncm-classic-encryption.md)：容器结构、密钥、元数据和音频的组织方式
- [经典 NCM 解密流程](docs/ncm-classic-decryption.md)：检测、解密与音频恢复过程
- [反馈问题](https://github.com/KaffuAlcaid/ncmBreaker/issues/new)：请附系统、程序版本、操作步骤和提示信息

## 许可与致谢

采用 [MIT License](LICENSE)

NCM Breaker 是独立开发的开源项目，与网易云音乐及其关联方无隶属或合作关系\
请在适用法律法规和服务协议允许的范围内，处理合法取得且有权转换的文件

由 [KaffuAlcaid](https://github.com/KaffuAlcaid) 开发维护

感谢 [charlotte-xiao/NCM2MP3](https://github.com/charlotte-xiao/NCM2MP3) 对经典 NCM 格式的分析。本项目参考了其中的格式和算法说明，代码、界面及其他功能均重新实现

感谢 [LwhJesse/Netease-Playlist-Exporter](https://github.com/LwhJesse/Netease-Playlist-Exporter) 提供的歌单获取与数据导出思路。本项目以 Java 实现相关功能，歌单导入与导出统一使用 XLSX 格式
