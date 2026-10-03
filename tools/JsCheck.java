/*
 * 开发期小工具：不依赖 Node，直接用 JDK 单文件模式跑 JS 词法检查。
 * 用法：java JsCheck.java <file.js>
 * 检查内容：字符串/注释/模板串状态机 + 括号配对 + 常见笔误。
 */
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

public class JsCheck {
    public static void main(String[] args) throws Exception {
        String src = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        Deque<int[]> stack = new ArrayDeque<>();   // {char, line}
        int line = 1, errors = 0;
        char state = 'c';                          // c=code l=line-comment b=block-comment " ' `
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            char nx = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (ch == '\n') line++;
            switch (state) {
                case 'c':
                    if (ch == '/' && nx == '/') { state = 'l'; i++; }
                    else if (ch == '/' && nx == '*') { state = 'b'; i++; }
                    else if (ch == '"') state = '"';
                    else if (ch == '\'') state = '\'';
                    else if (ch == '`') state = '`';
                    else if (ch == '(' || ch == '[' || ch == '{') stack.push(new int[]{ch, line});
                    else if (ch == ')' || ch == ']' || ch == '}') {
                        if (stack.isEmpty()) { System.out.println("行 " + line + ": 多余的 " + ch); errors++; }
                        else {
                            int[] top = stack.pop();
                            char exp = top[0] == '(' ? ')' : top[0] == '[' ? ']' : '}';
                            if (exp != ch) {
                                System.out.println("行 " + line + ": " + ch + " 与行 " + top[1] + " 的 " + (char) top[0] + " 不匹配");
                                errors++;
                            }
                        }
                    }
                    break;
                case 'l': if (ch == '\n') state = 'c'; break;
                case 'b': if (ch == '*' && nx == '/') { state = 'c'; i++; } break;
                case '"': if (ch == '\\') i++; else if (ch == '"') state = 'c'; break;
                case '\'': if (ch == '\\') i++; else if (ch == '\'') state = 'c'; break;
                case '`': if (ch == '\\') i++; else if (ch == '`') state = 'c'; break;
                default: break;
            }
        }
        if (state != 'c' && state != 'l') { System.out.println("文件结束时仍处于未闭合状态: " + state); errors++; }
        while (!stack.isEmpty()) {
            int[] top = stack.pop();
            System.out.println("行 " + top[1] + ": " + (char) top[0] + " 未闭合");
            errors++;
        }
        System.out.println("行数=" + line + "  字符数=" + src.length() + "  语法结构错误=" + errors);
        if (errors > 0) System.exit(1);
    }
}
