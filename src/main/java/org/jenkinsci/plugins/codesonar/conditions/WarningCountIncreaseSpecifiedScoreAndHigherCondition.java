package org.jenkinsci.plugins.codesonar.conditions;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Result;

import java.util.List;

import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.codesonar.CodeSonarBuildAction;
import org.jenkinsci.plugins.codesonar.models.CodeSonarBuildActionDTO;
import org.jenkinsci.plugins.codesonar.models.analysis.Analysis;
import org.jenkinsci.plugins.codesonar.models.analysis.Warning;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;

/**
 * @author andrius
 */
public class WarningCountIncreaseSpecifiedScoreAndHigherCondition extends Condition {

    private static final String NAME = "Warning count increase: specified score and higher";

    private int rankOfWarnings = 30;
    private String warningPercentage = String.valueOf(5.0f);
    private String warrantedResult = Result.UNSTABLE.toString();
    private String resultDescription = NAME;

    @DataBoundConstructor
    public WarningCountIncreaseSpecifiedScoreAndHigherCondition(int rankOfWarnings, String warningPercentage) {
        this.rankOfWarnings = rankOfWarnings;
        this.warningPercentage = warningPercentage;
    }
    
    public int getRankOfWarnings() {
        return rankOfWarnings;
    }

    public void setRankOfWarnings(int rankOfWarnings) {
        this.rankOfWarnings = rankOfWarnings;
    }

    public String getWarningPercentage() {
        return warningPercentage;
    }

    public void setWarningPercentage(String warningPercentage) {
        this.warningPercentage = warningPercentage;
    }

    public String getWarrantedResult() {
        return warrantedResult;
    }

    @DataBoundSetter
    public void setWarrantedResult(String warrantedResult) {
        this.warrantedResult = warrantedResult;
    }

    @Override
    public Result validate(CodeSonarBuildActionDTO current, CodeSonarBuildActionDTO previous, Launcher launcher, TaskListener listener) {
        if (current == null) {
            return Result.SUCCESS;
        }
        
        Analysis analysis = current.getAnalysisActiveWarnings();

        int totalNumberOfWarnings = analysis.getWarnings().size();

        float severeWarnings = 0.0f;
        List<Warning> warnings = analysis.getWarnings();
        for (Warning warning : warnings) {
            if (warning.getScore() > rankOfWarnings) {
                severeWarnings++;
            }
        }

        float calculatedWarningPercentage = (severeWarnings / totalNumberOfWarnings) * 100;
        float thresholdPercentage = Float.parseFloat(warningPercentage);

        if (calculatedWarningPercentage > thresholdPercentage) {
            resultDescription = String.format("More than %.2f%% warnings with score more than %d (%.2f%%, %d out of %d)", thresholdPercentage, rankOfWarnings, result, severeWarnings, totalNumberOfWarnings);
            return Result.fromString(warrantedResult);
        }

        resultDescription = String.format("At most %.2f%% warnings with score more than %d (%.2f%%, %d out of %d)", thresholdPercentage, rankOfWarnings, result, severeWarnings, totalNumberOfWarnings);
        return Result.SUCCESS;
    }

    @Override
    public String describeResult() {
        return resultDescription;
    }

    @Symbol("warningCountIncreaseSpecifiedScoreAndHigher")
    @Extension
    public static final class DescriptorImpl extends ConditionDescriptor<WarningCountIncreaseSpecifiedScoreAndHigherCondition> {

        public DescriptorImpl() {
            load();
        }

        @Override
        public @Nonnull String getDisplayName() {
            return NAME;
        }

        public FormValidation doCheckWarningPercentage(@QueryParameter("warningPercentage") String warningPercentage) {
            if (StringUtils.isBlank(warningPercentage)) {
                return FormValidation.error("Cannot be empty");
            }

            try {
                float v = Float.parseFloat(warningPercentage);

                if (v < 0) {
                    return FormValidation.error("The provided value must be zero or greater");
                }
            } catch (NumberFormatException numberFormatException) {
                return FormValidation.error("Not a valid decimal number");
            }

            return FormValidation.ok();
        }
    }
}
