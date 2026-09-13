package com.pocketagent.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, native-only Markdown model. Raw fenced code is never interpreted or rewritten. */
final class MarkdownBlocks {
    static final int BOLD=1, ITALIC=2, CODE=3, LINK=4, HEADING=5, STRIKE=6, QUOTE=7;
    private static final Pattern FENCE=Pattern.compile("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
    private static final Pattern HEADING_PREFIX=Pattern.compile("^ {0,3}#{1,6}[ \\t]+");
    private static final Pattern BULLET_PREFIX=Pattern.compile("^([ \\t]*)(?:[-+*])[ \\t]+");
    private static final Pattern ORDERED_PREFIX=Pattern.compile("^([ \\t]*)([0-9]{1,9})[.)][ \\t]+");
    static final class Block {
        final boolean code;
        final String text, language;
        Block(boolean code,String text,String language){this.code=code;this.text=text;this.language=language;}
    }
    static final class Mark {
        final int kind,start,end;
        final String target;
        Mark(int kind,int start,int end,String target){this.kind=kind;this.start=start;this.end=end;this.target=target;}
    }
    static final class Document {
        final String text;
        final List<Mark> marks;
        Document(String text,List<Mark> marks){this.text=text;this.marks=Collections.unmodifiableList(marks);}
    }
    static List<Block> parse(String markdown) {
        String value=markdown==null?"":markdown;
        ArrayList<Block> result=new ArrayList<>();
        int at=0,prose=0,body=0;char fence=0;int fenceLength=0;String language="";
        while(at<value.length()) {
            int newline=value.indexOf('\n',at),end=newline<0?value.length():newline,next=newline<0?end:end+1;
            String line=value.substring(at,end);if(line.endsWith("\r"))line=line.substring(0,line.length()-1);
            Matcher match=FENCE.matcher(line);
            if(fence==0&&match.matches()&&!(match.group(1).charAt(0)=='`'&&match.group(2).contains("`"))) {
                if(at>prose)result.add(new Block(false,value.substring(prose,at),""));
                fence=match.group(1).charAt(0);fenceLength=match.group(1).length();language=language(match.group(2));body=next;
            } else if(fence!=0&&match.matches()&&match.group(1).charAt(0)==fence
                    &&match.group(1).length()>=fenceLength&&match.group(2).trim().isEmpty()) {
                result.add(new Block(true,value.substring(body,at),language));fence=0;prose=next;
            }
            at=next;
        }
        if(fence!=0)result.add(new Block(true,value.substring(body),language));
        else if(prose<value.length())result.add(new Block(false,value.substring(prose),""));
        return result;
    }
    /** Prose only; exported code is taken directly from Block.text. */
    static Document document(String markdown) {
        String value=markdown==null?"":markdown;StringBuilder out=new StringBuilder();ArrayList<Mark> marks=new ArrayList<>();
        String[] lines=value.split("\n",-1);
        for(int i=0;i<lines.length;i++) {
            String line=lines[i];if(line.endsWith("\r"))line=line.substring(0,line.length()-1);
            boolean quote=false;
            if(line.matches("^ {0,3}>.*")){line=line.replaceFirst("^ {0,3}> ?","");quote=true;}
            Matcher heading=HEADING_PREFIX.matcher(line);boolean isHeading=heading.find();
            if(isHeading)line=line.substring(heading.end()).replaceFirst("[ \\t]+#+[ \\t]*$","");
            Matcher bullet=BULLET_PREFIX.matcher(line),ordered=ORDERED_PREFIX.matcher(line);
            if(bullet.find())line=bullet.group(1)+"• "+line.substring(bullet.end());
            else if(ordered.find())line=ordered.group(1)+ordered.group(2)+". "+line.substring(ordered.end());
            line=line.replaceFirst("^(\\s*• )\\[ \\] ","$1☐ ").replaceFirst("^(\\s*• )\\[[xX]\\] ","$1☑ ");
            int start=out.length();inline(line,0,line.length(),out,marks,0);
            if(isHeading)mark(marks,HEADING,start,out.length(),"");if(quote)mark(marks,QUOTE,start,out.length(),"");
            if(i+1<lines.length)out.append('\n');
        }
        return new Document(out.toString(),marks);
    }
    static String plainText(String markdown) {
        StringBuilder out=new StringBuilder();
        for(Block block:parse(markdown))out.append(block.code?block.text:document(block.text).text);
        return out.toString();
    }
    static String spokenText(String markdown) {
        StringBuilder out=new StringBuilder();
        for(Block block:parse(markdown))if(!block.code){if(out.length()>0)out.append('\n');out.append(document(block.text).text);}
        return out.toString().trim();
    }
    static String suggestedName(String language,int index) { return "code-"+Math.max(1,index)+"."+extension(language); }
    static String mimeType(String language) {
        String ext=extension(language);
        if(ext.equals("json"))return "application/json";
        if(ext.equals("xml"))return "application/xml";
        if(ext.equals("html"))return "text/html";
        if(ext.equals("css"))return "text/css";
        if(ext.equals("csv"))return "text/csv";
        return "text/plain";
    }
    static String extension(String input) {
        String lang=language(input);
        switch(lang) {
            case "java":return "java";case "kotlin":case "kt":return "kt";
            case "python":case "py":return "py";case "javascript":case "js":case "node":return "js";
            case "typescript":case "ts":return "ts";case "jsx":return "jsx";case "tsx":return "tsx";
            case "c":return "c";case "cpp":case "c++":return "cpp";case "csharp":case "c#":case "cs":return "cs";
            case "swift":return "swift";case "go":case "golang":return "go";case "rust":case "rs":return "rs";
            case "ruby":case "rb":return "rb";case "php":return "php";case "dart":return "dart";
            case "html":return "html";case "css":return "css";case "json":return "json";case "xml":return "xml";
            case "yaml":case "yml":return "yaml";case "markdown":case "md":return "md";case "sql":return "sql";
            case "shell":case "sh":case "bash":case "zsh":return "sh";case "powershell":case "ps1":return "ps1";
            case "toml":return "toml";case "csv":return "csv";case "text":case "plaintext":case "txt":default:return "txt";
        }
    }
    private static String language(String info) {
        String value=info==null?"":info.trim().split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        return value.length()<=32&&value.matches("[a-z0-9_+#.-]*")?value:"";
    }
    private static void inline(String s,int from,int end,StringBuilder out,List<Mark> marks,int depth) {
        if(depth>12){out.append(s,from,end);return;}
        int i=from;
        while(i<end) {
            char c=s.charAt(i);
            if(c=='\\'&&i+1<end&&isPunctuation(s.charAt(i+1))){out.append(s.charAt(i+1));i+=2;continue;}
            if(c=='`') {
                int count=run(s,i,end,'`'),close=findTick(s,i+count,end,count);
                if(close>=0){int start=out.length();String text=s.substring(i+count,close).replace('\n',' ');
                    if(text.startsWith(" ")&&text.endsWith(" ")&&!text.trim().isEmpty())text=text.substring(1,text.length()-1);
                    out.append(text);mark(marks,CODE,start,out.length(),"");i=close+count;continue;}
                out.append(s,i,i+count);i+=count;continue;
            }
            boolean image=c=='!'&&i+1<end&&s.charAt(i+1)=='[';
            if(c=='['||image) {
                int labelStart=i+(image?2:1),labelEnd=bracketEnd(s,labelStart,end);
                if(labelEnd>=0&&labelEnd+1<end&&s.charAt(labelEnd+1)=='(') {
                    int close=destinationEnd(s,labelEnd+2,end);
                    if(close>=0){String target=destination(s.substring(labelEnd+2,close));int start=out.length();
                        if(labelStart==labelEnd&&image)out.append("Image");else inline(s,labelStart,labelEnd,out,marks,depth+1);
                        if(!target.isEmpty())mark(marks,LINK,start,out.length(),target);i=close+1;continue;}
                }
            }
            if(c=='*'||c=='_'||c=='~') {
                int count=run(s,i,end,c),width=c=='~'?2:count>=3?3:count>=2?2:1;
                if((c!='~'||count>=2)&&i+width<end&&!Character.isWhitespace(s.charAt(i+width))
                        &&!(c=='_'&&i>from&&Character.isLetterOrDigit(s.charAt(i-1)))) {
                    int close=findDelimiter(s,i+width,end,c,width);
                    if(close>=0){int start=out.length();inline(s,i+width,close,out,marks,depth+1);
                        if(c=='~')mark(marks,STRIKE,start,out.length(),"");
                        else {if(width>=2)mark(marks,BOLD,start,out.length(),"");if(width==1||width==3)mark(marks,ITALIC,start,out.length(),"");}
                        i=close+width;continue;}
                }
            }
            out.append(c);i++;
        }
    }
    private static int run(String s,int at,int end,char c){int i=at;while(i<end&&s.charAt(i)==c)i++;return i-at;}
    private static int findTick(String s,int at,int end,int count) {
        for(int i=at;i<end;i++)if(s.charAt(i)=='`'){int n=run(s,i,end,'`');if(n==count)return i;i+=n-1;}return -1;
    }
    private static int findDelimiter(String s,int at,int end,char c,int width) {
        for(int i=at;i+width<=end;i++) {
            if(s.charAt(i)=='\\'){i++;continue;}
            if(s.charAt(i)=='`'){int n=run(s,i,end,'`'),close=findTick(s,i+n,end,n);if(close>=0){i=close+n-1;continue;}}
            if(s.charAt(i)==c) {
                int n=run(s,i,end,c);
                if(i>at&&!Character.isWhitespace(s.charAt(i-1))&&n>=width
                        &&!(c=='_'&&i+n<end&&Character.isLetterOrDigit(s.charAt(i+n))))return i+n-width;
                i+=n-1;
            }
        }return -1;
    }
    private static int bracketEnd(String s,int from,int end) {
        int depth=0;for(int i=from;i<end;i++){char c=s.charAt(i);if(c=='\\'){i++;continue;}
            if(c=='[')depth++;else if(c==']'){if(depth==0)return i;depth--;}}
        return -1;
    }
    private static int destinationEnd(String s,int from,int end) {
        int depth=0;boolean angle=false,quote=false;
        for(int i=from;i<end;i++){char c=s.charAt(i);if(c=='\\'){i++;continue;}
            if(c=='<'&&!quote)angle=true;else if(c=='>'&&angle)angle=false;
            else if(c=='"'&&!angle)quote=!quote;
            else if(!angle&&!quote){if(c=='(')depth++;else if(c==')'){if(depth==0)return i;depth--;}}}
        return -1;
    }
    private static String destination(String raw) {
        String text=raw.trim();
        if(text.startsWith("<")){int close=text.indexOf('>');return close>0?text.substring(1,close):"";}
        int space=text.indexOf(' ');if(space>=0)text=text.substring(0,space);
        return text.replace("\\(","(").replace("\\)",")").replace("\\\\","\\");
    }
    private static boolean isPunctuation(char c){return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c)>=0;}
    private static void mark(List<Mark> marks,int kind,int start,int end,String target){if(end>start)marks.add(new Mark(kind,start,end,target));}
    private MarkdownBlocks(){}
}
