package com.example.server.dto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 VideoContext 与 AnalysisResult 渲染成带关键视频帧的 Markdown。
 * 产物形状对齐通义听悟 KeyFrameList：一条记录 = 起止时间 + 一张图 + 一段文字。
 *
 * 本类是纯函数，不触发任何外部 IO（不调 ASR / LLM / 对象存储），可在无网络环境下编译并断言验证。
 */
public final class VideoNoteRenderer {

    private VideoNoteRenderer() {
    }

    /** 分析结果 + 时间顺序笔记（笔记含关键帧）。 */
    public static String render(VideoContext context, AnalysisResult result) {
        String analysis = renderAnalysis(context, result);
        String notes = renderNotes(context);
        if (analysis.isEmpty()) {
            return notes;
        }
        if (notes.isEmpty()) {
            return analysis;
        }
        return analysis + "\n" + notes;
    }

    /** 只渲染分析结果，其中每条证据附上它所在时刻的画面帧。 */
    public static String renderAnalysis(VideoContext context, AnalysisResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder markdown = new StringBuilder("## ").append(result.title()).append("\n\n## 核心结论\n");
        result.conclusions().forEach(item -> markdown.append("- ").append(item).append('\n'));

        markdown.append("\n## 视频证据\n");
        Set<String> postedFrames = new HashSet<>();
        for (AnalysisResult.Evidence evidence : result.evidence()) {
            markdown.append("- [").append(formatTime(evidence.timestampMs())).append("] ")
                    .append(evidence.source()).append("：").append(evidence.content()).append('\n');
            String frame = firstFrame(segmentOf(context, evidence.timestampMs()));
            if (frame != null && postedFrames.add(frame)) {
                markdown.append("  ![](").append(frame).append(")\n");
            }
        }

        markdown.append("\n## 建议\n");
        result.suggestions().forEach(item -> markdown.append("- ").append(item).append('\n'));
        return markdown.toString();
    }

    /** 按时间顺序渲染笔记：画面没变的相邻时间窗并入同一页，一页只出一张代表帧。 */
    public static String renderNotes(VideoContext context) {
        if (context == null || context.segments().isEmpty()) {
            return "";
        }
        List<Page> pages = buildPages(context.segments());
        StringBuilder markdown = new StringBuilder("## 视频笔记（按时间顺序）\n");
        for (Page page : pages) {
            markdown.append("\n### [").append(formatTime(page.startMs)).append(" - ")
                    .append(formatTime(page.endMs)).append("]\n");
            if (page.frame != null) {
                markdown.append("![](").append(page.frame).append(")\n");
            }
            for (String ocrText : page.ocrTexts) {
                markdown.append("- 画面文字：").append(ocrText).append('\n');
            }
            String transcript = page.transcript.toString().trim();
            if (!transcript.isEmpty()) {
                markdown.append("- 讲解：").append(transcript).append('\n');
            }
        }
        return markdown.toString();
    }

    /**
     * 合并规则（去重驱动，不设数量硬上限）：
     * 当前窗无帧，或代表帧与上一页相同，都视为画面未变，并入上一页。
     */
    private static List<Page> buildPages(List<VideoContext.VideoSegment> segments) {
        List<Page> pages = new ArrayList<>();
        for (VideoContext.VideoSegment segment : segments) {
            String frame = firstFrame(segment);
            Page last = pages.isEmpty() ? null : pages.get(pages.size() - 1);
            if (last != null && (frame == null || frame.equals(last.frame))) {
                last.extend(segment);
            } else {
                pages.add(new Page(segment, frame));
            }
        }
        return pages;
    }

    private static VideoContext.VideoSegment segmentOf(VideoContext context, long timestampMs) {
        if (context == null || timestampMs < 0) {
            return null;
        }
        for (VideoContext.VideoSegment segment : context.segments()) {
            if (timestampMs >= segment.startMs() && timestampMs < segment.endMs()) {
                return segment;
            }
        }
        return null;
    }

    private static String firstFrame(VideoContext.VideoSegment segment) {
        if (segment == null || segment.evidenceFrames().isEmpty()) {
            return null;
        }
        for (String frame : segment.evidenceFrames()) {
            if (frame != null && !frame.isBlank()) {
                return frame.trim();
            }
        }
        return null;
    }

    private static String formatTime(long timestampMs) {
        long seconds = Math.max(0L, timestampMs) / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    /** 一页 = 画面基本不变的一段连续时间，对应通义 KeyFrameList 里的一条记录。 */
    private static final class Page {

        private final long startMs;
        private final String frame;
        private final Set<String> ocrTexts = new LinkedHashSet<>();
        private final StringBuilder transcript = new StringBuilder();
        private long endMs;

        private Page(VideoContext.VideoSegment segment, String frame) {
            this.startMs = segment.startMs();
            this.endMs = segment.endMs();
            this.frame = frame;
            extend(segment);
        }

        private void extend(VideoContext.VideoSegment segment) {
            this.endMs = Math.max(this.endMs, segment.endMs());
            segment.ocrTexts().stream()
                    .filter(text -> text != null && !text.isBlank())
                    .forEach(ocrTexts::add);
            String talk = segment.transcript();
            if (talk != null && !talk.isBlank()) {
                if (transcript.length() > 0) {
                    transcript.append(' ');
                }
                transcript.append(talk.trim());
            }
        }
    }
}
