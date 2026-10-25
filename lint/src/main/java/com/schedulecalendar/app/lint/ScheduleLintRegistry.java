package com.schedulecalendar.app.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.Vendor;
import com.android.tools.lint.detector.api.ApiKt;
import com.android.tools.lint.detector.api.Issue;
import java.util.Arrays;
import java.util.List;

/** 注册本项目的自定义 Lint 规则。 */
public class ScheduleLintRegistry extends IssueRegistry {

    @Override
    public List<Issue> getIssues() {
        return Arrays.asList(
                LogUsageDetector.ISSUE_RAW_LOG,
                LogUsageDetector.ISSUE_PARAMETERIZED_TAG);
    }

    @Override
    public int getApi() {
        return ApiKt.CURRENT_API;
    }

    @Override
    public Vendor getVendor() {
        return new Vendor("ScheduleCalendar", "com.schedulecalendar.app.lint", "internal");
    }
}
