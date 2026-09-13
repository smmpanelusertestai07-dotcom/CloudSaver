package com.pocketagent.mobile;

/** Compiled official browser destinations, reviewed 2026-09-12. No provider preference API is emulated. */
final class ProviderAccountLinks {
    static final class Link {
        final String id, title, detail, url;
        Link(String id, String title, String detail, String url) {
            this.id = id; this.title = title; this.detail = detail; this.url = url;
        }
    }
    private ProviderAccountLinks() {}
    static Link[] forProvider(String provider) {
        if ("codex".equals(provider)) return new Link[]{
            new Link("account", "Account & security", "In ChatGPT, open Settings → Account or Security.", "https://chatgpt.com/"),
            new Link("usage", "Usage & credits", "Your official Codex allowance and credit controls. Sign in if asked.", "https://chatgpt.com/codex/settings/usage"),
            new Link("connections", "Connected apps", "In ChatGPT, open Settings → Apps to review or disconnect access.", "https://chatgpt.com/"),
            new Link("privacy", "Privacy & model training", "OpenAI's privacy portal includes the personal-account training opt-out.", "https://privacy.openai.com/"),
            new Link("environment", "Codex environment training", "A separate Codex setting; changing ChatGPT training does not change this choice.", "https://chatgpt.com/codex/cloud/settings/general")
        };
        if ("claude".equals(provider)) return new Link[]{
            new Link("account", "Account & security", "Open Claude's settings and review your account and sign-in controls.", "https://claude.ai/settings/general"),
            new Link("usage", "Usage & credits", "Your Claude allowance and eligible extra-usage controls.", "https://claude.ai/settings/usage"),
            new Link("connections", "Connected apps", "In Claude, open Customize → Connectors to review access.", "https://claude.ai/"),
            new Link("privacy", "Privacy & model training", "Review the actual Help Improve our AI models choice in Claude.", "https://claude.ai/settings/data-privacy-controls")
        };
        if ("cursor".equals(provider)) return new Link[]{
            new Link("account", "Account & billing", "Manage your subscription in Cursor's official dashboard.", "https://cursor.com/dashboard/billing"),
            new Link("usage", "Usage & spending", "Open Usage in the Cursor dashboard; account limits remain with Cursor.", "https://cursor.com/dashboard"),
            new Link("connections", "Connections & account settings", "Open the dashboard's settings to review available connected services.", "https://cursor.com/dashboard"),
            new Link("privacy", "Privacy Mode · official guide", "Cursor documents this setting in General; a team admin can enforce it. This opens instructions.", "https://cursor.com/help/security-and-privacy/privacy")
        };
        if ("antigravity".equals(provider)) return new Link[]{
            new Link("account", "Google account", "Manage the Google account you use with Antigravity.", "https://myaccount.google.com/"),
            new Link("security", "Google account security", "Review sign-in protection and account security on Google.", "https://myaccount.google.com/security"),
            new Link("usage", "Google AI credits", "Google's credit page. Antigravity quota is shown in its own settings.", "https://one.google.com/ai/credits"),
            new Link("connections", "Google account connections", "Review services with access to your Google account.", "https://myaccount.google.com/connections"),
            new Link("privacy", "Google data & privacy", "Google account controls are separate from Antigravity's own data settings.", "https://myaccount.google.com/data-and-privacy"),
            new Link("settings", "Antigravity settings · guide", "Open official instructions; PocketAgent does not read its training preference.", "https://antigravity.google/docs/settings/")
        };
        return new Link[0];
    }
    static boolean allows(String provider, String destination) {
        if (destination == null) return false;
        for (Link link : forProvider(provider)) if (link.url.equals(destination)) return true;
        return false;
    }
    static String note(String provider) {
        if ("antigravity".equals(provider)) return "Google account management only. PocketAgent's adapter for Antigravity's event stream is not written yet.";
        return "These open the provider's website. PocketAgent does not read or change its privacy choices.";
    }
}
