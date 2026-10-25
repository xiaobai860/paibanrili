package com.schedulecalendar.app.lint;

import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.SourceCodeScanner;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;

/**
 * 日志规范实时守护（配合 test 里的 LoggingConventionTest 守护测试）。
 *
 * 拦两类：
 * 1. 裸 android.util.Log.* —— 不受 BuildConfig.DIAGNOSTIC_LOGS / LOG_TO_FILE 门控 →
 *    release 包会打印，且来源不可辨。唯一允许出现在 AppLog.kt 封装内部。
 * 2. AppLog.* 调用的 TAG 参数不是 LogTags.XXX 常量 —— 参数化/硬编码 TAG 会绕开
 *    LogTags 规范与守护测试，线上按 TAG 过滤日志时无法定位来源。
 *
 * 与单测的区别：单测是「提交/发版前」的事后闸门；本规则让 Android Studio 在
 * 写代码时**当场标红**，从源头避免回归。
 */
public class LogUsageDetector extends Detector implements SourceCodeScanner {

    public static final Issue ISSUE_RAW_LOG = Issue.create(
            "RawAndroidLog",
            "禁止使用裸 android.util.Log",
            "日志必须统一走 AppLog（自动受 DIAGNOSTIC_LOGS / LOG_TO_FILE 编译期开关门控）；"
                    + "裸 Log.* 不受开关门控，release 包会打印，且来源不可辨。"
                    + "封装实现只允许写在 domain/util/AppLog.kt 内部。",
            Category.CORRECTNESS,
            8,
            Severity.ERROR,
            new Implementation(LogUsageDetector.class, Scope.JAVA_FILE_SCOPE));

    public static final Issue ISSUE_PARAMETERIZED_TAG = Issue.create(
            "LogTagMustBeConstant",
            "AppLog 的 TAG 必须是 LogTags 常量",
            "TAG 必须写成 LogTags.XXX 常量（不是字符串字面量、也不是变量）。"
                    + "参数化/硬编码 TAG 会绕开 LogTags 规范与 LoggingConventionTest 守护测试，"
                    + "且来源不可辨。",
            Category.CORRECTNESS,
            8,
            Severity.ERROR,
            new Implementation(LogUsageDetector.class, Scope.JAVA_FILE_SCOPE));

    /** 同时匹配 Log 与 AppLog 的方法名（d/i/w/e/v），在 visit 内按所属类分流。 */
    @Override
    public List<String> getApplicableMethodNames() {
        return Arrays.asList("v", "d", "i", "w", "e");
    }

    @Override
    public void visitMethodCall(JavaContext context, UCallExpression node, PsiMethod method) {
        PsiClass cls = method.getContainingClass();
        if (cls == null) return;
        String qn = cls.getQualifiedName();
        if (qn == null) return;

        if ("android.util.Log".equals(qn)) {
            // 仅封装本体允许裸调用
            java.io.File curFile = context.getLocation(node).getFile();
            if (curFile != null && "AppLog.kt".equals(curFile.getName())) return;
            context.report(ISSUE_RAW_LOG, node, context.getLocation(node),
                    "禁止裸用 android.util.Log（请改用 AppLog，自动受开关门控）");
        } else if ("com.schedulecalendar.app.domain.util.AppLog".equals(qn)) {
            List<UExpression> args = node.getValueArguments();
            if (args.isEmpty()) return;
            String src = args.get(0).asSourceString().trim();
            if (!src.startsWith("LogTags.")) {
                context.report(ISSUE_PARAMETERIZED_TAG, node, context.getLocation(node),
                        "TAG 必须是 LogTags.XXX 常量，而不是 \"" + src + "\"");
            }
        }
    }
}
