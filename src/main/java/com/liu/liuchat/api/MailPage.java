package com.liu.liuchat.api;

import java.util.List;

/** 一页邮箱内容。 */
public record MailPage(List<Mail> mails, int page, int pageSize, int total, int unread) {
    public MailPage {
        mails = mails == null ? List.of() : List.copyOf(mails);
    }

    public boolean hasNext() {
        return (long) page * pageSize < total;
    }
}
