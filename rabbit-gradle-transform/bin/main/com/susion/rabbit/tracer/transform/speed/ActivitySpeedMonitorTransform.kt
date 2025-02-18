package com.susion.rabbit.tracer.transform.speed

import com.google.auto.service.AutoService
import com.susion.rabbit.tracer.transform.GlobalConfig
import com.susion.rabbit.tracer.transform.core.RabbitClassTransformer
import com.susion.rabbit.tracer.transform.core.context.ArtifactManager
import com.susion.rabbit.tracer.transform.core.context.TransformContext
import com.susion.rabbit.tracer.transform.core.rxentension.className
import com.susion.rabbit.tracer.transform.core.rxentension.find
import com.susion.rabbit.tracer.transform.utils.ComponentHandler
import com.susion.rabbit.tracer.transform.utils.RabbitTransformPrinter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import javax.xml.parsers.SAXParserFactory

/**
 * susionwang at 2019-11-15
 * 在onCreate方法运行完毕时插入监控代码
 */
@AutoService(RabbitClassTransformer::class)
class ActivitySpeedMonitorTransform : RabbitClassTransformer {

    private val ACTIVITY_SPEED_MONITOR_CLASS =
        "com/susion/rabbit/monitor/instance/ActivitySpeedMonitor"

    //ActivitySpeedMonitor.wrapperViewOnActivityCreateEnd()
    private val wapperMethodName = "wrapperViewOnActivityCreateEnd"
    private val wapperMethodDesc = "(Landroid/app/Activity;)V"

    //ActivitySpeedMonitor.activityCreateStart()
    private val acCreateStartName = "activityCreateStart"
    private val acCreateStartDesc = "(Landroid/app/Activity;)V"

    //ActivitySpeedMonitor.activityResumeEnd()
    private val acResumeEndName = "activityResumeEnd"
    private val acResumeEndDesc = "(Landroid/app/Activity;)V"

    //activity onCreate 方法
    private val METHOD_ON_CREATE_NAME = "onCreate"
    private val METHOD_ONCREATE_DESC = "(Landroid/os/Bundle;)V"

    //activity onResume 方法
    private val METHOD_ON_RESUME_NAME = "onResume"
    private val METHOD_ON_RESUME_DESC = "()V"

    private val activityList = mutableSetOf<String>()

    override fun onPreTransform(context: TransformContext) {
        val parser = SAXParserFactory.newInstance().newSAXParser()
        context.artifacts.get(ArtifactManager.MERGED_MANIFESTS).forEach { manifest ->
            val handler = ComponentHandler()
            parser.parse(manifest, handler)
            activityList.addAll(handler.activities)
        }
    }

    override fun transform(context: TransformContext, klass: ClassNode): ClassNode {

        if (!activityList.contains(klass.className) || !prefixInConfig(klass)) {
            return klass
        }

        insertCodeToActivityOnCreate(klass)

        insertCodeToActivityOnResume(klass)

        return klass

    }

    private fun prefixInConfig(klass: ClassNode): Boolean {

        val className = klass.className

        val configList = GlobalConfig.monitorPkgNamePrefixList

        if (configList.isEmpty()) return true

        configList.forEach {
            if (className.startsWith(it)) {
                return true
            }
        }

        return false
    }

    private fun insertCodeToActivityOnCreate(klass: ClassNode) {
        val onCreateMethod = klass.methods?.find {
            "${it.name}${it.desc}" == "$METHOD_ON_CREATE_NAME$METHOD_ONCREATE_DESC"
        } ?: return

        onCreateMethod.instructions?.find(Opcodes.RETURN)?.apply {
            RabbitTransformPrinter.p("ActivitySpeedMonitorTransform : insert code to wrap activity content view ---> ${klass.name}")
            onCreateMethod.instructions?.insertBefore(this, VarInsnNode(Opcodes.ALOAD, 0)) //参数
            onCreateMethod.instructions?.insertBefore(this, getWrapSpeedViewMethod())
        }

        onCreateMethod.instructions?.find(Opcodes.ALOAD)?.apply {
            RabbitTransformPrinter.p("ActivitySpeedMonitorTransform : insert code to on activity create ---> ${klass.name}")
            onCreateMethod.instructions?.insertBefore(this, VarInsnNode(Opcodes.ALOAD, 0)) //参数
            onCreateMethod.instructions?.insertBefore(this, getAcCreateStateMethod())
        }
    }

    private fun insertCodeToActivityOnResume(klass: ClassNode) {
        var onResumeMethod = klass.methods?.find {
            "${it.name}${it.desc}" == "$METHOD_ON_RESUME_NAME$METHOD_ON_RESUME_DESC"
        }

        if (onResumeMethod == null) {
            onResumeMethod = getDefaultOnResumeMethod(klass)
            klass.methods.add(onResumeMethod)
        }

        onResumeMethod.instructions?.find(Opcodes.RETURN)?.apply {
            RabbitTransformPrinter.p("insert code to  ${onResumeMethod.name} --- ${klass.name}")
            onResumeMethod.instructions?.insertBefore(this, VarInsnNode(Opcodes.ALOAD, 0)) //参数
            onResumeMethod.instructions?.insertBefore(this, getAcResumeStateMethod())
        }
    }

    private fun getAcResumeStateMethod(): MethodInsnNode {
        return MethodInsnNode(
            Opcodes.INVOKESTATIC,
            ACTIVITY_SPEED_MONITOR_CLASS,
            acResumeEndName,
            acResumeEndDesc,
            false
        )
    }

    private fun getWrapSpeedViewMethod() = MethodInsnNode(
        Opcodes.INVOKESTATIC,
        ACTIVITY_SPEED_MONITOR_CLASS,
        wapperMethodName,
        wapperMethodDesc,
        false
    )

    private fun getAcCreateStateMethod() = MethodInsnNode(
        Opcodes.INVOKESTATIC,
        ACTIVITY_SPEED_MONITOR_CLASS,
        acCreateStartName,
        acCreateStartDesc,
        false
    )

    private fun getDefaultOnResumeMethod(klass: ClassNode): MethodNode {
        RabbitTransformPrinter.p("new onResume() Method --> super class name : ${klass.superName}  --->")
        return MethodNode(
            Opcodes.ACC_PROTECTED,
            METHOD_ON_RESUME_NAME,
            METHOD_ON_RESUME_DESC,
            null,
            null
        ).apply {
            instructions.add(InsnList().apply {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(
                    MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        klass.superName,
                        METHOD_ON_RESUME_NAME,
                        METHOD_ON_RESUME_DESC,
                        false
                    )
                )
                add(InsnNode(Opcodes.RETURN))
            })
            maxStack = 1
        }
    }

}