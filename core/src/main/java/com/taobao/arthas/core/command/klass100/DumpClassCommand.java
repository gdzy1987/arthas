package com.taobao.arthas.core.command.klass100;

import com.alibaba.arthas.deps.org.slf4j.Logger;
import com.alibaba.arthas.deps.org.slf4j.LoggerFactory;
import com.taobao.arthas.core.command.Constants;
import com.taobao.arthas.core.command.model.ClassVO;
import com.taobao.arthas.core.command.model.DumpClassModel;
import com.taobao.arthas.core.command.model.MessageModel;
import com.taobao.arthas.core.command.model.RowAffectModel;
import com.taobao.arthas.core.command.model.StatusModel;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.cli.CompletionUtils;
import com.taobao.arthas.core.shell.command.AnnotatedCommand;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.ClassUtils;
import com.taobao.arthas.core.util.InstrumentationUtils;
import com.taobao.arthas.core.util.SearchUtils;
import com.taobao.arthas.core.util.affect.RowAffect;
import com.taobao.middleware.cli.annotations.Argument;
import com.taobao.middleware.cli.annotations.DefaultValue;
import com.taobao.middleware.cli.annotations.Description;
import com.taobao.middleware.cli.annotations.Name;
import com.taobao.middleware.cli.annotations.Option;
import com.taobao.middleware.cli.annotations.Summary;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Dump class byte array
 */
@Name("dump")
@Summary("Dump class byte array from JVM")
@Description(Constants.EXAMPLE +
        "  dump java.lang.String\n" +
        "  dump -d /tmp/output java.lang.String\n" +
        "  dump org/apache/commons/lang/StringUtils\n" +
        "  dump *StringUtils\n" +
        "  dump -E org\\\\.apache\\\\.commons\\\\.lang\\\\.StringUtils\n" +
        Constants.WIKI + Constants.WIKI_HOME + "dump")
public class DumpClassCommand extends AnnotatedCommand {
    private static final Logger logger = LoggerFactory.getLogger(DumpClassCommand.class);

    private String classPattern;
    private String code = null;
    private boolean isRegEx = false;

    private String directory;

    private int limit;

    @Argument(index = 0, argName = "class-pattern")
    @Description("Class name pattern, use either '.' or '/' as separator")
    public void setClassPattern(String classPattern) {
        this.classPattern = classPattern;
    }

    @Option(shortName = "c", longName = "code")
    @Description("The hash code of the special class's classLoader")
    public void setCode(String code) {
        this.code = code;
    }

    @Option(shortName = "E", longName = "regex", flag = true)
    @Description("Enable regular expression to match (wildcard matching by default)")
    public void setRegEx(boolean regEx) {
        isRegEx = regEx;
    }

    @Option(shortName = "d", longName = "directory")
    @Description("Sets the destination directory for class files")
    public void setDirectory(String directory) {
        this.directory = directory;
    }

    @Option(shortName = "l", longName = "limit")
    @Description("The limit of dump classes size, default value is 5")
    @DefaultValue("50")
    public void setLimit(int limit) {
        this.limit = limit;
    }

    @Override
    public StatusModel process(CommandProcess process) {
        if (directory != null) {
            File dir = new File(directory);
            if (dir.isFile()) {
                return StatusModel.failure(-1, directory + " :is not a directory, please check it");
            }
        }
        Instrumentation inst = process.session().getInstrumentation();
        Set<Class<?>> matchedClasses = SearchUtils.searchClass(inst, classPattern, isRegEx, code);
        if (matchedClasses == null || matchedClasses.isEmpty()) {
            return processNoMatch(process);
        } else if (matchedClasses.size() > limit) {
            return processMatches(process, matchedClasses);
        } else {
            return processMatch(process, inst, matchedClasses);
        }
    }

    @Override
    public void complete(Completion completion) {
        if (!CompletionUtils.completeClassName(completion)) {
            super.complete(completion);
        }
    }

    private StatusModel processMatch(CommandProcess process, Instrumentation inst, Set<Class<?>> matchedClasses) {
        RowAffect effect = new RowAffect();
        try {
            Map<Class<?>, File> classFiles = dump(inst, matchedClasses);
            List<ClassVO> dumpedClasses = new ArrayList<ClassVO>(classFiles.size());
            for (Map.Entry<Class<?>, File> entry : classFiles.entrySet()) {
                Class<?> clazz = entry.getKey();
                File file = entry.getValue();
                ClassVO classVO = ClassUtils.createSimpleClassInfo(clazz);
                classVO.setLocation(file.getCanonicalPath());
                dumpedClasses.add(classVO);
                effect.rCnt(1);
            }
            process.appendResult(new DumpClassModel().setDumpedClassFiles(dumpedClasses));

            return StatusModel.success();
        } catch (Throwable t) {
            logger.error("dump: fail to dump classes: " + matchedClasses, t);
            return StatusModel.failure(-1, "dump: fail to dump classes: " + matchedClasses);
        } finally {
            process.appendResult(new RowAffectModel(effect));
        }
    }

    private StatusModel processMatches(CommandProcess process, Set<Class<?>> matchedClasses) {
        String msg = String.format(
                "Found more than %d class for: %s, Please Try to specify the classloader with the -c option, or try to use --limit option.",
                limit, classPattern);
        process.appendResult(new MessageModel(msg));

        List<ClassVO> classVOs = ClassUtils.createClassVOList(matchedClasses);
        process.appendResult(new DumpClassModel().setMatchedClasses(classVOs));
        return StatusModel.failure(-1, msg);
    }

    private StatusModel processNoMatch(CommandProcess process) {
        return StatusModel.failure(-1, "No class found for: " + classPattern);
    }

    private Map<Class<?>, File> dump(Instrumentation inst, Set<Class<?>> classes) throws UnmodifiableClassException {
        ClassDumpTransformer transformer = null;
        if (directory != null) {
            transformer = new ClassDumpTransformer(classes, new File(directory));
        } else {
            transformer = new ClassDumpTransformer(classes);
        }
        InstrumentationUtils.retransformClasses(inst, transformer, classes);
        return transformer.getDumpResult();
    }
}
