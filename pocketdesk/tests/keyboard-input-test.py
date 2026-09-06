#!/usr/bin/env python3
"""Exercise production IME translation with Android API doubles, without a phone keyboard.

These tests prove callback count, edit ordering and Unicode preservation; VNC wire transport
and device keyboards have separate validation.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = PROJECT / 'app/src/com/pocketlinux/KeyboardInputView.java'
KEY_NAMES = sorted(set(re.findall(r'KeyEvent\.(KEYCODE_\w+)', SOURCE.read_text())))
STUBS = {
    'android/content/Context.java': 'package android.content; public class Context {}',
    'android/util/AttributeSet.java': 'package android.util; public interface AttributeSet {}',
    'android/text/InputType.java': '''package android.text; public class InputType {
public static final int TYPE_CLASS_TEXT=1,TYPE_TEXT_FLAG_NO_SUGGESTIONS=2,TYPE_TEXT_VARIATION_VISIBLE_PASSWORD=4;
}''',
    'android/view/KeyEvent.java': '''package android.view; public class KeyEvent {
public static final int ACTION_DOWN=0,ACTION_UP=1;
%s
private final int action,key,unicode;
public KeyEvent(int action,int key,int unicode) { this.action=action;this.key=key;this.unicode=unicode; }
public int getAction(){return action;} public int getKeyCode(){return key;} public int getUnicodeChar(){return unicode;}
}''' % '\n'.join('public static final int %s=%d;' % (name, i + 1) for i, name in enumerate(KEY_NAMES)),
    'android/view/View.java': '''package android.view; public class View {
public View(android.content.Context context){} public View(android.content.Context context,android.util.AttributeSet attrs){}
public void setFocusable(boolean value){} public void setFocusableInTouchMode(boolean value){}
public boolean onCheckIsTextEditor(){return false;}
public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info){return null;}
public boolean onKeyDown(int code,KeyEvent event){return false;}
}''',
    'android/view/inputmethod/EditorInfo.java': '''package android.view.inputmethod; public class EditorInfo {
public static final int IME_FLAG_NO_EXTRACT_UI=1,IME_FLAG_NO_FULLSCREEN=2,IME_ACTION_NONE=4;
public int inputType,imeOptions;
}''',
    'android/view/inputmethod/InputConnection.java': '''package android.view.inputmethod; public interface InputConnection {
boolean commitText(CharSequence text,int position); boolean setComposingText(CharSequence text,int position);
boolean finishComposingText(); boolean deleteSurroundingText(int before,int after);
boolean deleteSurroundingTextInCodePoints(int before,int after); boolean performEditorAction(int action);
boolean sendKeyEvent(android.view.KeyEvent event);
}''',
    'android/view/inputmethod/BaseInputConnection.java': '''package android.view.inputmethod;
public class BaseInputConnection implements InputConnection {
public BaseInputConnection(android.view.View view,boolean full){}
public boolean commitText(CharSequence text,int position){return false;}
public boolean setComposingText(CharSequence text,int position){return false;}
public boolean finishComposingText(){return false;} public boolean deleteSurroundingText(int before,int after){return false;}
public boolean deleteSurroundingTextInCodePoints(int before,int after){return false;}
public boolean performEditorAction(int action){return false;} public boolean sendKeyEvent(android.view.KeyEvent event){return false;}
}''',
    'com/pocketlinux/KeyboardInputHarness.java': r'''package com.pocketlinux;
import android.view.KeyEvent;
import android.view.inputmethod.*;
public class KeyboardInputHarness implements KeyboardInputView.Listener {
 int changes,typed,specials,backspaces,deletes,lastKey; String text="";
 public void typeCodePoint(int point){typed++;lastKey=point;}
 public void specialKey(int key){specials++;lastKey=key;}
 public void replaceText(int backspaces,int deletes,String text){changes++;this.backspaces=backspaces;this.deletes=deletes;this.text=text;}
 void reset(){changes=typed=specials=backspaces=deletes=lastKey=0;text="";}
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  KeyboardInputHarness seen=new KeyboardInputHarness();
  KeyboardInputView view=new KeyboardInputView(new android.content.Context());
  view.setListener(seen); InputConnection input=view.onCreateInputConnection(new EditorInfo());
  switch(args[0]) {
   case "large": {
    StringBuilder value=new StringBuilder();for(int i=0;i<3000;i++)value.append("hello🙂\n世界");
    String prompt=value.toString();input.commitText(prompt,1);
    require(seen.changes==1 && seen.typed==0 && seen.specials==0,"large commit made per-key callbacks");
    require(seen.backspaces==0 && seen.deletes==0 && prompt.equals(seen.text),"large prompt lost Unicode/newlines");
    break;
   }
   case "composition":
    input.setComposingText("A🙂Z",1);seen.reset(); input.setComposingText("A🙃Q",1);
    require(seen.changes==1 && seen.backspaces==2 && "🙃Q".equals(seen.text),"surrogate-prefix replacement changed wrong characters");
    seen.reset();input.commitText("A🙃Q",1);require(seen.changes==0,"commit retyped an unchanged composition");break;
   case "utf16_delete":
    input.setComposingText("A🙂",1);seen.reset();input.deleteSurroundingText(1,0);
    require(seen.changes==1 && seen.backspaces==1 && seen.text.isEmpty(),"half-surrogate delete did not erase whole emoji");
    seen.reset();input.setComposingText("AB",1);
    require(seen.changes==1 && seen.backspaces==0 && "B".equals(seen.text),"delete left an unpaired surrogate");break;
   case "codepoint_delete":
    input.setComposingText("A🙂Z",1);seen.reset();input.deleteSurroundingTextInCodePoints(2,3);
    require(seen.changes==1 && seen.backspaces==2 && seen.deletes==3 && seen.text.isEmpty(),"code-point deletion split or over-deleted composition");
    seen.reset();input.setComposingText("AB",1);require("B".equals(seen.text) && seen.backspaces==0,"code-point composition cursor wrong");break;
   case "large_delete":
    input.setComposingText("ab",1);seen.reset();input.deleteSurroundingText(12000,7000);
    require(seen.changes==1 && seen.backspaces==12000 && seen.deletes==7000,"large deletion enqueued per-key edits");
    require(seen.specials==0 && seen.typed==0,"large deletion bypassed batch");break;
   case "finish":
    input.setComposingText("word",1);seen.reset();input.finishComposingText();input.commitText(" next\n",1);
    require(seen.changes==1 && seen.backspaces==0 && " next\n".equals(seen.text),"finish composing removed previous word or newline");break;
   case "keys":
    input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_TAB,0));
    require(seen.specials==1 && seen.lastKey==0xff09,"physical Tab changed semantics");
    input.performEditorAction(0);require(seen.specials==2 && seen.lastKey==0xff0d,"IME Enter changed semantics");
    input.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,999,0x1f642));
    require(seen.typed==1 && seen.lastKey==0x1f642 && seen.changes==0,"physical Unicode key changed semantics");break;
   case "empty":
    input.setComposingText("🙂",1);seen.reset();input.commitText(null,1);
    require(seen.changes==1 && seen.backspaces==1 && seen.text.isEmpty(),"empty commit failed to clear composition");
    seen.reset();input.deleteSurroundingText(-1,-1);require(seen.changes==0,"negative deletion was forwarded");break;
   default:throw new AssertionError("unknown case");
  }
 }
}''',
}


class KeyboardInputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory(prefix='pocketdesk-keyboard-test-')
        cls.root = Path(cls.work.name)
        sources = [str(SOURCE)]
        for name, content in STUBS.items():
            path = cls.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            sources.append(str(path))
        compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
        subprocess.run(compiler + ['-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', str(cls.root / 'classes')] + sources,
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def run_case(self, name):
        result = subprocess.run(['java', '-cp', str(self.root / 'classes'), 'com.pocketlinux.KeyboardInputHarness', name],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_many_thousand_character_commit_is_one_lossless_edit(self): self.run_case('large')
    def test_composition_replaces_only_changed_unicode_tail(self): self.run_case('composition')
    def test_utf16_deletion_does_not_leave_half_an_emoji(self): self.run_case('utf16_delete')
    def test_codepoint_deletion_preserves_remaining_composition(self): self.run_case('codepoint_delete')
    def test_many_thousand_deletions_are_one_edit(self): self.run_case('large_delete')
    def test_finish_composing_preserves_word_and_next_newline(self): self.run_case('finish')
    def test_individual_physical_keys_keep_their_meaning(self): self.run_case('keys')
    def test_empty_commit_and_invalid_deletion(self): self.run_case('empty')


if __name__ == '__main__':
    unittest.main()
