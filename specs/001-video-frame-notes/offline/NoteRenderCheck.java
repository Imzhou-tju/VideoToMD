package com.example.server.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线断言程序：逐条核对 spec.md 的 AC1-AC8。
 *
 * 编译条件：只需要 JDK，不需要 Maven / Spring / 网络 / API Key。
 * 运行方式见 specs/001-video-frame-notes/verify.md。
 */
public final class NoteRenderCheck {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        if (args.length > 0 && "sample".equals(args[0])) {
            System.out.print(sample());
            return;
        }
        ac1AndAc4();
        ac2();
        ac3();
        ac5();
        ac6();
        ac7();
        System.out.println("----");
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // AC1 每个含帧的页输出恰好一张图，URL 取自该窗 evidenceFrames；AC4 时间格式与区间
    private static void ac1AndAc4() {
        VideoContext ctx = new VideoContext("demo.mp4", "总结遍历", List.of(
                seg(0, 60_000, "开场介绍二叉树", List.of("二叉树"), List.of("f1.jpg")),
                seg(60_000, 120_000, "前序遍历定义", List.of("前序遍历：根、左、右"), List.of("f2.jpg")),
                seg(120_000, 180_000, "中序遍历", List.of(), List.of("f3.jpg"))));
        String notes = VideoNoteRenderer.renderNotes(ctx);

        check("AC1 three pages", count(notes, "### [") == 3);
        check("AC1 one image per page", imagesPerPageAreExactlyOne(notes));
        check("AC1 url from evidenceFrames",
                notes.contains("![](f1.jpg)") && notes.contains("![](f2.jpg)") && notes.contains("![](f3.jpg)"));
        check("AC4 time format",
                notes.contains("### [00:00 - 01:00]")
                        && notes.contains("### [01:00 - 02:00]")
                        && notes.contains("### [02:00 - 03:00]"));
        check("AC4 no mm:ss drift", !notes.contains("### [00:00 - 00:00]"));
    }

    // AC2 无帧的窗不输出图片语法
    private static void ac2() {
        VideoContext ctx = new VideoContext("demo.mp4", "g", List.of(
                seg(0, 60_000, "无声片头", List.of("标题页"), List.of())));
        String notes = VideoNoteRenderer.renderNotes(ctx);
        check("AC2 no image syntax", !notes.contains("![]("));
        check("AC2 keeps text", notes.contains("- 讲解：无声片头") && notes.contains("- 画面文字：标题页"));

        VideoContext mixed = new VideoContext("demo.mp4", "g", List.of(
                seg(0, 60_000, "a", List.of(), List.of("f1.jpg")),
                seg(60_000, 120_000, "b", List.of(), List.of())));
        String mixedNotes = VideoNoteRenderer.renderNotes(mixed);
        check("AC2 no empty link", !mixedNotes.contains("![]()"));
        check("AC2 frameless window merged into previous page", count(mixedNotes, "### [") == 1);
    }

    // AC3 相邻窗代表帧相同则只输出一次
    private static void ac3() {
        VideoContext ctx = new VideoContext("demo.mp4", "g", List.of(
                seg(0, 60_000, "a", List.of("同一页PPT"), List.of("f1.jpg")),
                seg(60_000, 120_000, "b", List.of("同一页PPT"), List.of("f1.jpg")),
                seg(120_000, 180_000, "c", List.of("同一页PPT"), List.of("f1.jpg"))));
        String notes = VideoNoteRenderer.renderNotes(ctx);

        check("AC3 single page", count(notes, "### [") == 1);
        check("AC3 single image", count(notes, "![](") == 1);
        check("AC3 merged range", notes.contains("### [00:00 - 03:00]"));
        check("AC3 merged transcript", notes.contains("- 讲解：a b c"));
        check("AC3 ocr dedup", count(notes, "- 画面文字：同一页PPT") == 1);
    }

    // AC5 每条证据后附其时间戳所在窗的代表帧；查不到则不附；同窗同帧只贴一次
    private static void ac5() {
        VideoContext ctx = new VideoContext("demo.mp4", "g", List.of(
                seg(0, 60_000, "a", List.of(), List.of("f1.jpg")),
                seg(60_000, 120_000, "b", List.of(), List.of("f2.jpg"))));
        AnalysisResult result = new AnalysisResult("标题", List.of("结论"), List.of(
                new AnalysisResult.Evidence(0, "ASR", "提到前序遍历"),
                new AnalysisResult.Evidence(30_000, "ASR", "同一窗第二条"),
                new AnalysisResult.Evidence(65_000, "OCR", "写出遍历顺序"),
                new AnalysisResult.Evidence(999_999, "ASR", "越界时间戳")),
                List.of("建议"));

        String md = VideoNoteRenderer.renderAnalysis(ctx, result);
        check("AC5 evidence with frame", md.contains("- [00:00] ASR：提到前序遍历\n  ![](f1.jpg)"));
        check("AC5 second window frame", md.contains("- [01:05] OCR：写出遍历顺序\n  ![](f2.jpg)"));
        check("AC5 same window posts once", count(md, "![](f1.jpg)") == 1);
        check("AC5 out of range posts nothing", !hasFrameAfter(md, "- [16:39] ASR：越界时间戳"));
        check("AC5 boundary endMs excluded",
                VideoNoteRenderer.renderAnalysis(ctx, new AnalysisResult("t", List.of(),
                        List.of(new AnalysisResult.Evidence(60_000, "ASR", "边界")), List.of()))
                        .contains("- [01:00] ASR：边界\n  ![](f2.jpg)"));
    }

    // AC6 纯函数 + 空值/空集合不抛异常
    private static void ac6() {
        VideoContext ctx = new VideoContext("demo.mp4", "g", List.of(
                seg(0, 60_000, "a", List.of("x"), List.of("f1.jpg"))));
        AnalysisResult result = new AnalysisResult("t", List.of("c"),
                List.of(new AnalysisResult.Evidence(1_000, "ASR", "e")), List.of("s"));

        check("AC6 pure function", VideoNoteRenderer.render(ctx, result).equals(VideoNoteRenderer.render(ctx, result)));

        String nullContext = VideoNoteRenderer.render(null, result);
        check("AC6 null context no crash", nullContext.contains("- [00:01] ASR：e"));
        check("AC6 null context no notes", !nullContext.contains("## 视频笔记"));

        VideoContext empty = new VideoContext("demo.mp4", "g", List.of());
        check("AC6 empty segments", VideoNoteRenderer.renderNotes(empty).isEmpty());
        check("AC6 empty segments full render", VideoNoteRenderer.render(empty, result).contains("## 核心结论"));

        String nullResult = VideoNoteRenderer.render(ctx, null);
        check("AC6 null result returns notes only", nullResult.startsWith("## 视频笔记"));
        check("AC6 null result no leading blank", !nullResult.startsWith("\n"));

        // VideoSegment 构造器用 List.copyOf，null 元素进不来，因此只测空串/空白串
        VideoContext noFrames = new VideoContext("demo.mp4", "g", List.of(
                seg(0, 60_000, "a", List.of(), List.of("", "   "))));
        check("AC6 blank frames ignored", VideoNoteRenderer.renderNotes(noFrames).contains("### [00:00 - 01:00]"));
        check("AC6 blank frames no image", !VideoNoteRenderer.renderNotes(noFrames).contains("![]("));
    }

    // AC7 AnalysisResult.toMarkdown() 行为不变，且不含图片
    private static void ac7() {
        AnalysisResult result = new AnalysisResult("标题", List.of("结论一", "结论二"),
                List.of(new AnalysisResult.Evidence(65_000, "ASR", "原文")),
                List.of("建议一"));
        String expected = "## 标题\n"
                + "\n## 核心结论\n"
                + "- 结论一\n"
                + "- 结论二\n"
                + "\n## 视频证据\n"
                + "- [01:05] ASR：原文\n"
                + "\n## 建议\n"
                + "- 建议一\n";
        check("AC7 toMarkdown unchanged", result.toMarkdown().equals(expected));
        check("AC7 toMarkdown has no image", !result.toMarkdown().contains("![]("));
    }

    /** 打印一份完整产物样例，用于人工核对 Markdown 形态（对齐通义 KeyFrameList 的一条记录一节）。 */
    private static String sample() {
        VideoContext ctx = new VideoContext("lecture.mp4", "总结这节课讲了什么", List.of(
                seg(0, 60_000, "今天我们讲二叉树的遍历", List.of("第 1 页 二叉树"), List.of("https://minio/frame_000001.jpg")),
                seg(60_000, 120_000, "前序遍历先访问根节点", List.of("前序遍历：根节点、左子树、右子树"),
                        List.of("https://minio/frame_000312.jpg")),
                seg(120_000, 180_000, "再看中序遍历，它先访问左子树", List.of("中序遍历：左子树、根节点、右子树"),
                        List.of("https://minio/frame_000688.jpg")),
                seg(180_000, 240_000, "这页停留了很久，画面没变", List.of(), List.of("https://minio/frame_000688.jpg"))));
        AnalysisResult result = new AnalysisResult("二叉树遍历讲解",
                List.of("课程按前序、中序两个顺序展开，各配一页板书"),
                List.of(new AnalysisResult.Evidence(62_000, "ASR", "前序遍历先访问根节点"),
                        new AnalysisResult.Evidence(125_000, "OCR", "中序遍历：左子树、根节点、右子树")),
                List.of("复习后序遍历"));
        return VideoNoteRenderer.render(ctx, result);
    }

    private static VideoContext.VideoSegment seg(long start, long end, String transcript,
                                                 List<String> ocr, List<String> frames) {
        return new VideoContext.VideoSegment(start, end, transcript, ocr, frames);
    }

    private static boolean imagesPerPageAreExactlyOne(String notes) {
        List<String> pages = new ArrayList<>();
        int idx = 0;
        while (true) {
            int next = notes.indexOf("### [", idx);
            if (next < 0) {
                break;
            }
            int end = notes.indexOf("### [", next + 5);
            pages.add(notes.substring(next, end < 0 ? notes.length() : end));
            idx = next + 5;
        }
        if (pages.isEmpty()) {
            return false;
        }
        for (String page : pages) {
            if (count(page, "![](") != 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasFrameAfter(String markdown, String evidenceLine) {
        String[] lines = markdown.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].equals(evidenceLine)) {
                return lines[i + 1].trim().startsWith("![](");
            }
        }
        return false;
    }

    private static int count(String text, String token) {
        int n = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            n++;
            idx += token.length();
        }
        return n;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }
}
