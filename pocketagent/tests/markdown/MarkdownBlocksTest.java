package com.pocketagent.mobile;

import java.util.List;

public final class MarkdownBlocksTest {
    private static int assertions;
    public static void main(String[] args) {
        List<MarkdownBlocks.Block> blocks=MarkdownBlocks.parse("Before\n```java\nclass Test {\n  String s = \"**literal** <script>\";\n}\n```\nAfter");
        equal(3,blocks.size());equal("java",blocks.get(1).language);
        equal("class Test {\n  String s = \"**literal** <script>\";\n}\n",blocks.get(1).text);
        equal("Before\nAfter",MarkdownBlocks.spokenText("Before\n```java\nsecretCode();\n```\nAfter").replace("\n\n","\n"));
        equal("before\nA\r\n  B\r\nafter",MarkdownBlocks.plainText("before\n```text\r\nA\r\n  B\r\n```\r\nafter"));
        blocks=MarkdownBlocks.parse("````java\n```\nA\n```\n````\n");equal(1,blocks.size());equal("```\nA\n```\n",blocks.get(0).text);
        blocks=MarkdownBlocks.parse("~~~python\na=1\n~~~~\n");equal("python",blocks.get(0).language);equal("a=1\n",blocks.get(0).text);
        blocks=MarkdownBlocks.parse("start\n```ts\nconst unclosed = true;");equal(2,blocks.size());equal("const unclosed = true;",blocks.get(1).text);
        blocks=MarkdownBlocks.parse("```java\nhi\n```not-a-close\n``\n");equal("hi\n```not-a-close\n``\n",blocks.get(0).text);
        blocks=MarkdownBlocks.parse("    ```java\nindented\n");equal(false,blocks.get(0).code);
        equal("Escaped *bold* and `tick`",MarkdownBlocks.plainText("Escaped \\*bold\\* and \\`tick\\`"));
        equal("one **literal** two",MarkdownBlocks.plainText("one ``**literal**`` two"));
        equal("Title\n• Strong item\n2. Next\nQuoted link",MarkdownBlocks.plainText("## Title\n- **Strong** item\n2) Next\n> Quoted [link](https://example.com/a_(b))"));
        equal("• ☑ Done\n• ☐ To do",MarkdownBlocks.plainText("- [x] Done\n* [ ] To do"));
        MarkdownBlocks.Document doc=MarkdownBlocks.document("**bold *italic*** and *italic **bold*** and ***both***");
        equal("bold italic and italic bold and both",doc.text);
        yes(has(doc,MarkdownBlocks.BOLD,"bold italic"));yes(has(doc,MarkdownBlocks.ITALIC,"italic"));
        yes(has(doc,MarkdownBlocks.BOLD,"bold"));yes(has(doc,MarkdownBlocks.ITALIC,"italic bold"));
        yes(has(doc,MarkdownBlocks.BOLD,"both"));yes(has(doc,MarkdownBlocks.ITALIC,"both"));
        doc=MarkdownBlocks.document("**[bold `code`](<https://example.com/a b> \"title\")**");
        equal("bold code",doc.text);yes(has(doc,MarkdownBlocks.BOLD,"bold code"));yes(has(doc,MarkdownBlocks.CODE,"code"));
        equal("https://example.com/a b",doc.marks.stream().filter(m->m.kind==MarkdownBlocks.LINK).findFirst().get().target);
        equal("snake_case and ~~unfinished",MarkdownBlocks.plainText("snake_case and ~~unfinished"));
        equal("old",MarkdownBlocks.plainText("~~old~~"));
        equal("<script>alert('literal')</script>",MarkdownBlocks.plainText("<script>alert('literal')</script>"));
        equal("code-1.java",MarkdownBlocks.suggestedName("JAVA",1));
        equal("code-2.py",MarkdownBlocks.suggestedName("python",2));
        equal("code-3.txt",MarkdownBlocks.suggestedName("../../auth.json",3));
        equal("code-1.txt",MarkdownBlocks.suggestedName("\n/secret.java",-3));
        equal("kt",MarkdownBlocks.extension("kotlin"));equal("tsx",MarkdownBlocks.extension("tsx"));
        equal("application/json",MarkdownBlocks.mimeType("json"));equal("text/plain",MarkdownBlocks.mimeType("unknown"));
        equal("",MarkdownBlocks.plainText(null));equal("",MarkdownBlocks.spokenText("```sh\nrm -rf nothing\n```"));
        System.out.println("MarkdownBlocksTest: "+assertions+" assertions passed");
    }
    private static boolean has(MarkdownBlocks.Document d,int kind,String text){for(MarkdownBlocks.Mark m:d.marks)if(m.kind==kind&&d.text.substring(m.start,m.end).equals(text))return true;return false;}
    private static void yes(boolean value){assertions++;if(!value)throw new AssertionError("Expected true");}
    private static void equal(Object expected,Object actual){assertions++;if(!expected.equals(actual))throw new AssertionError("Expected: "+expected+"\nActual: "+actual);}
}
