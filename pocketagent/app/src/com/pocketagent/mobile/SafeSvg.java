package com.pocketagent.mobile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** Strict static-shape SVG subset. Returns fresh markup, never the supplied document. */
final class SafeSvg {
    static final int MAX_BYTES=512*1024, MAX_NODES=1024, MAX_DEPTH=24;
    private static final String SVG="http://www.w3.org/2000/svg";
    private static final Set<String> ELEMENTS=new HashSet<>(Arrays.asList("svg","g","path","rect","circle","ellipse","line","polyline","polygon","title","desc"));
    private static final Set<String> GEOMETRY=new HashSet<>(Arrays.asList("x","y","width","height","rx","ry","r","cx","cy","x1","x2","y1","y2","stroke-width","stroke-miterlimit","stroke-dashoffset","pathLength"));
    private static final Pattern NUMBER=Pattern.compile("[+-]?(?:[0-9]{1,8}(?:\\.[0-9]{0,8})?|\\.[0-9]{1,8})(?:[eE][+-]?[0-9]{1,2})?%?");
    private static final Pattern PATH=Pattern.compile("[MmZzLlHhVvCcSsQqTtAa0-9eE.,+\\-\\s]{1,262144}");
    private static final Pattern COLOR=Pattern.compile("(?:#[a-fA-F0-9]{3,8}|[A-Za-z]{1,24}|rgba?\\([0-9.% ,]+\\))");
    static String sanitize(byte[] bytes,int foreground)throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("SVG preview limit: 512 KB.");
        String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        String lower=raw.toLowerCase(Locale.ROOT);
        // Reject before any XML parser sees declarations. Entities are never needed for geometry.
        if(raw.indexOf('\0')>=0||lower.contains("<!doctype")||lower.contains("<!entity")||lower.contains("<?xml-stylesheet")||raw.indexOf('&')>=0)
            throw new IOException("This SVG contains unsupported declarations or entities.");
        try {
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
            // Android parsers differ in feature support. Pre-rejection plus a rejecting resolver
            // remain mandatory; these flags additionally harden platforms that expose them.
            feature(factory,"http://apache.org/xml/features/disallow-doctype-decl",true);
            feature(factory,"http://xml.org/sax/features/external-general-entities",false);
            feature(factory,"http://xml.org/sax/features/external-parameter-entities",false);
            feature(factory,"http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            DocumentBuilder parser=factory.newDocumentBuilder();parser.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("External XML resources are blocked.");});
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});
            Document document=parser.parse(new org.xml.sax.InputSource(new java.io.StringReader(raw)));
            if(document.getDoctype()!=null)throw new IOException("SVG document types are blocked.");
            Element root=document.getDocumentElement();
            if(root==null||!name(root).equals("svg"))throw new IOException("This file is not an SVG image.");
            StringBuilder out=new StringBuilder();int[] nodes={0};write(root,out,0,nodes,foreground);
            return out.toString();
        }catch(IOException error){throw error;}catch(Exception error){throw new IOException("This SVG uses unsupported markup. You can save or share the original file.",error);}
    }
    private static void feature(DocumentBuilderFactory factory,String key,boolean value){try{factory.setFeature(key,value);}catch(javax.xml.parsers.ParserConfigurationException|RuntimeException ignored){}}
    private static String name(Node node)throws IOException{
        String namespace=node.getNamespaceURI();if(namespace!=null&&!namespace.isEmpty()&&!SVG.equals(namespace))throw new IOException("Foreign SVG elements are blocked.");
        String name=node.getLocalName();return name==null?node.getNodeName():name;
    }
    private static void write(Element element,StringBuilder out,int depth,int[] count,int foreground)throws IOException{
        if(depth>MAX_DEPTH||++count[0]>MAX_NODES)throw new IOException("This SVG is too complex to preview.");
        String tag=name(element);if(!ELEMENTS.contains(tag)||tag.equals("svg")&&depth!=0)throw new IOException("This SVG uses unsupported elements.");
        out.append('<').append(tag);
        if(depth==0)out.append(" xmlns=\"").append(SVG).append("\" color=\"#").append(String.format(Locale.ROOT,"%06x",foreground&0xffffff)).append("\" width=\"100%\" height=\"100%\"");
        NamedNodeMap attributes=element.getAttributes();if(attributes.getLength()>40)throw new IOException("Too many SVG attributes.");
        for(int i=0;i<attributes.getLength();i++){
            Node attribute=attributes.item(i);String key=attribute.getNodeName(),value=attribute.getNodeValue();
            if(key.equals("xmlns")){if(!SVG.equals(value))throw new IOException("Invalid SVG namespace.");continue;}
            if(key.equals("xmlns:xlink")){if(!value.equals("http://www.w3.org/1999/xlink"))throw new IOException("Invalid SVG namespace.");continue;}
            if(key.equals("id")){if(!value.matches("[A-Za-z_][A-Za-z0-9_.-]{0,63}"))throw new IOException("Invalid SVG identifier.");continue;}
            if(key.equals("version")){if(!value.equals("1.0")&&!value.equals("1.1")&&!value.equals("2.0"))throw new IOException("Unsupported SVG version.");continue;}
            if(key.indexOf(':')>=0||key.toLowerCase(Locale.ROOT).startsWith("on"))throw new IOException("Active SVG attributes are blocked.");
            if(depth==0&&(key.equals("width")||key.equals("height"))){numeric(value);continue;}
            if(key.equals("d")){if(!tag.equals("path")||!PATH.matcher(value).matches())throw new IOException("Invalid SVG path.");path(value);}
            else if(key.equals("viewBox")){numbers(value,4,4);}
            else if(key.equals("points")){numbers(value,2,8192);}
            else if(GEOMETRY.contains(key)){numeric(value);}
            else if(key.equals("fill")||key.equals("stroke")||key.equals("color")){if(value.length()>80||!COLOR.matcher(value).matches())throw new IOException("Unsupported SVG color.");}
            else if(key.equals("opacity")||key.equals("fill-opacity")||key.equals("stroke-opacity")){numeric(value);double number=Double.parseDouble(value);if(number<0||number>1)throw new IOException("Invalid SVG opacity.");}
            else if(key.equals("fill-rule")||key.equals("clip-rule")){if(!value.equals("nonzero")&&!value.equals("evenodd"))throw new IOException("Invalid SVG fill rule.");}
            else if(key.equals("stroke-linecap")){if(!value.matches("butt|round|square"))throw new IOException("Invalid stroke cap.");}
            else if(key.equals("stroke-linejoin")){if(!value.matches("miter|round|bevel"))throw new IOException("Invalid stroke join.");}
            else if(key.equals("stroke-dasharray")){if(!value.equals("none"))numbers(value,1,128);}
            else if(key.equals("transform")){transform(value);}
            else if(key.equals("preserveAspectRatio")){if(!value.matches("none|x(?:Min|Mid|Max)Y(?:Min|Mid|Max)(?: (?:meet|slice))?"))throw new IOException("Invalid SVG aspect ratio.");}
            else if(key.equals("role")){if(!value.equals("img"))throw new IOException("Invalid SVG role.");continue;}
            else if(key.equals("aria-hidden")){if(!value.equals("true")&&!value.equals("false"))throw new IOException("Invalid SVG accessibility attribute.");continue;}
            else throw new IOException("This SVG uses unsupported attributes.");
            // Values passed the closed grammar; escaping is still applied defensively.
            out.append(' ').append(key).append("=\"").append(escape(value)).append('"');
        }
        out.append('>');
        for(Node child=element.getFirstChild();child!=null;child=child.getNextSibling()){
            if(child.getNodeType()==Node.ELEMENT_NODE){if(!tag.equals("svg")&&!tag.equals("g"))throw new IOException("Invalid nested SVG shape.");write((Element)child,out,depth+1,count,foreground);}
            else if(child.getNodeType()==Node.TEXT_NODE||child.getNodeType()==Node.CDATA_SECTION_NODE){String value=child.getNodeValue();if(tag.equals("title")||tag.equals("desc")){if(value.length()>1024)throw new IOException("SVG description is too large.");out.append(escape(value));}else if(!value.trim().isEmpty())throw new IOException("Unexpected SVG text.");}
            else if(child.getNodeType()!=Node.COMMENT_NODE)throw new IOException("Unsupported SVG document content.");
        }
        out.append("</").append(tag).append('>');
    }
    private static void path(String value)throws IOException{
        java.util.regex.Matcher tokens=Pattern.compile("[MmZzLlHhVvCcSsQqTtAa]|[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|[,\\s]+").matcher(value);
        int end=0,count=0;while(tokens.find()){if(tokens.start()!=end||++count>20000)throw new IOException("SVG path is too complex.");String token=tokens.group();if(token.length()!=1||!Character.isLetter(token.charAt(0))){if(!token.matches("[,\\s]+"))numeric(token);}end=tokens.end();}if(end!=value.length())throw new IOException("Invalid SVG path.");
    }
    private static void numeric(String value)throws IOException{if(value==null||value.length()>32||!NUMBER.matcher(value).matches())throw new IOException("Invalid SVG number.");String raw=value.endsWith("%")?value.substring(0,value.length()-1):value;double number=Double.parseDouble(raw);if(!Double.isFinite(number)||Math.abs(number)>1_000_000)throw new IOException("SVG geometry exceeds preview bounds.");}
    private static void numbers(String value,int min,int max)throws IOException{if(value.length()>128*1024)throw new IOException("SVG geometry is too large.");String[] parts=value.trim().split("[ ,\\s]+");if(parts.length<min||parts.length>max)throw new IOException("Invalid SVG geometry.");for(String part:parts)numeric(part);}
    private static void transform(String value)throws IOException{if(value.length()>2048)throw new IOException("SVG transform is too large.");java.util.regex.Matcher matcher=Pattern.compile("(matrix|translate|scale|rotate|skewX|skewY)\\(([^()]*)\\)").matcher(value);int end=0,count=0;while(matcher.find()){if(!value.substring(end,matcher.start()).trim().matches(",?"))throw new IOException("Invalid SVG transform.");String type=matcher.group(1);int min=1,max=2;if(type.equals("matrix"))min=max=6;else if(type.equals("rotate"))max=3;else if(type.startsWith("skew"))max=1;numbers(matcher.group(2),min,max);end=matcher.end();if(++count>24)throw new IOException("Too many SVG transforms.");}if(count==0||!value.substring(end).trim().isEmpty())throw new IOException("Invalid SVG transform.");}
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private SafeSvg(){}
}
